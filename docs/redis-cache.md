# Redis Cache-Aside 전환 실습

## 개요

기존 팀 프로젝트에서 사용하던 Caffeine 로컬 캐시를  
별도 브랜치 `feature/redis-cache`에서 Redis 기반 Cache-Aside 구조로 변경했습니다.

Caffeine은 애플리케이션 인스턴스의 로컬 메모리를 사용하므로,  
서버가 여러 대로 확장되면 각 인스턴스가 서로 다른 캐시를 가질 수 있습니다.

한 인스턴스에서 상품이 수정되어 캐시를 삭제하더라도  
다른 인스턴스에는 이전 캐시 값이 남을 수 있습니다.

이를 개선하기 위해 여러 인스턴스가 공유할 수 있는 Redis를 캐시 저장소로 사용했습니다.

기존 `@Cacheable`, `@CacheEvict` 구조를 유지하고 캐시 구현체만 Redis로 변경할 수도 있지만,  
이번 실습에서는 Redis의 Cache Hit/Miss, TTL, 저장, 삭제 흐름을 직접 확인하기 위해  
`RedisTemplate`으로 Cache-Aside 패턴을 직접 구현했습니다.

---

## 기존 구조

- Spring Cache
- `@Cacheable`
- `@CacheEvict`
- Caffeine
- 애플리케이션 로컬 메모리 캐시

---

## 변경 구조

- Redis
- `RedisTemplate`
- Cache-Aside 직접 구현
- TTL 5분
    - 기존 Caffeine의 `expireAfterWrite=300s`와 동일
- 상품 수정 시 검색 캐시 전체 무효화

---

## 실행 환경

Redis는 Docker 컨테이너로 실행했습니다.

```bash
docker run -d -p 6379:6379 --name redis-container redis:latest
```

Redis 실행 확인:

```bash
docker exec -it redis-container redis-cli
```

```redis
PING
```

정상 응답:

```text
PONG
```

Spring Boot에서는 다음과 같이 Redis 연결 정보를 설정했습니다.

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
```

로컬 환경에서는 기본적으로 `localhost:6379`을 사용합니다.

Docker Compose와 같이 컨테이너 간 통신이 필요한 환경에서는  
`REDIS_HOST` 환경 변수로 Redis 호스트를 지정할 수 있도록 구성했습니다.

---

## 조회 흐름

```text
상품 조회 요청
    ↓
Redis 조회
    ↓
캐시 존재?
   ├─ YES → Cache Hit → Redis 데이터 반환
   │
   └─ NO  → Cache Miss
                ↓
             DB 조회
                ↓
             Redis 저장
                ↓
               반환
```

Cache Hit인 경우 DB를 다시 조회하지 않고 Redis에 저장된 값을 반환합니다.

Cache Miss인 경우 DB에서 상품 목록을 조회한 뒤  
다음 요청을 위해 조회 결과를 Redis에 저장합니다.

---

## 캐시 무효화

상품 정보가 변경되면 기존 검색 결과가 오래된 값을 반환할 수 있으므로  
`product:condition:*` 검색 캐시를 전체 삭제하도록 구현했습니다.

```text
상품 수정
    ↓
DB 데이터 변경
    ↓
Redis 검색 캐시 삭제
    ↓
다음 조회에서 Cache Miss
    ↓
DB의 최신 데이터 조회
    ↓
Redis 캐시 재생성
```

기존 Caffeine에서 사용하던

```java
@CacheEvict(value = "product_condition", allEntries = true)
```

역할을 Redis에서는 `ProductCacheService.deleteAll()`이 직접 수행합니다.

---

## 설계 결정

### 1. Redis 전용 캐시 DTO 사용

`ProductConditionCache`를 Redis 캐시 전용 DTO로 사용했습니다.

현재 RedisTemplate은 다음 타입을 사용합니다.

```java
RedisTemplate<String, Object>
```

직렬화 설정에서는 다음 옵션을 사용했습니다.

```java
ObjectMapper.DefaultTyping.NON_FINAL
```

`NON_FINAL`은 final이 아닌 타입을 중심으로 타입 정보를 기록합니다.

Java `record`는 암묵적으로 `final`이기 때문에  
현재 `RedisTemplate<String, Object>` 구조에서는 최상위 타입 정보가 충분히 남지 않아  
역직렬화 시 일반 Map 형태로 복원될 위험이 있습니다.

이 경우 `ProductConditionCache`로 직접 캐스팅할 때 타입 복원 문제가 발생할 수 있으므로,  
현재 직렬화 설정에 맞춰 캐시 전용 객체는 일반 `class`로 구성했습니다.

---

### 2. Page를 직접 Redis에 저장하지 않음

상품 검색 결과의 기존 반환 타입은 다음과 같습니다.

```java
Page<SearchProductConditionResponse>
```

Spring의 `PageImpl`을 그대로 캐싱하면 JSON 역직렬화가 복잡해지고  
Redis 캐시 데이터가 Spring 내부 구현체에 강하게 결합됩니다.

따라서 Redis에는 필요한 데이터만 저장합니다.

```text
content
page
size
totalElements
```

저장할 때:

```text
Page
 ↓
ProductConditionCache
 ↓
Redis
```

조회할 때:

```text
Redis
 ↓
ProductConditionCache
 ↓
PageImpl
```

형태로 변환합니다.

---

### 3. 상품 수정 시 검색 캐시 전체 삭제

하나의 상품은 여러 검색 조건 결과에 동시에 포함될 수 있습니다.

예를 들어 하나의 상품이 다음 캐시에 모두 포함될 수 있습니다.

```text
전체 상품 조회
FASHION 카테고리 조회
가격 10,000 ~ 50,000원 조회
카테고리 + 가격 복합 조회
```

상품 하나를 수정했을 때 해당 상품이 포함된 모든 Cache Key를 정확히 추적하기 어렵기 때문에  
기존 `@CacheEvict(allEntries = true)`와 동일하게 검색 캐시 전체를 무효화했습니다.

캐시 Key Prefix는 다음과 같습니다.

```text
product:condition:
```

예시:

```text
product:condition:0:9:null:null:ALL
```

현재 `deleteAll()`은 학습 목적으로 `KEYS product:condition:*`을 사용합니다.

운영 환경에서는 전체 Key Space 탐색 부담을 줄이기 위해  
`SCAN` 기반 방식으로 개선할 수 있습니다.

---

### 4. TTL 유지

기존 Caffeine의 `expireAfterWrite=300s`와 Redis의 TTL을 모두 5분으로 맞춰  
캐시 저장소만 변경했을 때의 동작을 비교할 수 있도록 만료 조건을 동일하게 유지했습니다.

---

## 주요 클래스

### `RedisConfig`

Spring Boot와 Redis 연결 및 직렬화를 설정합니다.

주요 역할:

- `RedisTemplate<String, Object>` Bean 생성
- Key를 문자열 형태로 직렬화
- Value를 JSON 형태로 직렬화
- `LocalDate`, `LocalDateTime` 직렬화 지원

---

### `ProductConditionCache`

상품 검색 결과를 Redis에 저장하기 위한 캐시 전용 DTO입니다.

역할:

```text
Page → Redis 저장 DTO
Redis 저장 DTO → Page
```

변환을 담당합니다.

---

### `ProductCacheService`

Redis에 직접 접근하는 역할을 담당합니다.

주요 메서드:

```text
get()
→ Redis 조회

save()
→ Redis 저장 + TTL 설정

deleteAll()
→ 상품 검색 캐시 전체 삭제
```

---

### `ProductService`

Cache-Aside 흐름을 제어합니다.

```text
Redis 조회
    ↓
Cache Hit / Miss 판단
    ↓
필요한 경우 DB 조회
    ↓
Redis 저장
```

상품 수정 시에는 `ProductCacheService`를 이용해  
기존 상품 검색 캐시를 무효화합니다.

---

## 실동작 검증

### 1. 첫 조회 — Cache Miss

요청:

```http
GET http://localhost:8080/products/cache?page=0&size=9
```

로그:

```text
[Redis] Cache MISS key=0:9:null:null:ALL → DB 조회
```

Cache가 존재하지 않아 실제 상품 조회 SQL이 실행되는 것을 확인했습니다.

---

### 2. Redis Key 생성 확인

Redis CLI:

```redis
KEYS product:condition:*
```

결과:

```text
1) "product:condition:0:9:null:null:ALL"
```

DB 조회 결과가 Redis에 정상적으로 저장된 것을 확인했습니다.

---

### 3. TTL 확인

Redis CLI:

```redis
TTL product:condition:0:9:null:null:ALL
```

결과 예시:

```text
(integer) 95
```

TTL이 계속 감소하는 것을 통해  
5분 만료 정책이 정상적으로 적용된 것을 확인했습니다.

---

### 4. 동일 조건 재조회 — Cache Hit

같은 요청을 다시 호출했습니다.

```http
GET http://localhost:8080/products/cache?page=0&size=9
```

로그:

```text
[Redis] Cache HIT key=0:9:null:null:ALL
```

두 번째 요청에서는 상품 조회 SQL이 다시 실행되지 않고  
Redis의 데이터를 반환하는 것을 확인했습니다.

---

### 5. 상품 수정 후 캐시 무효화

상품 수정 API는 `ADMIN` 또는 `OP_ADMIN` 권한의 JWT가 필요합니다.

```http
PATCH /api/product/{productId}
Authorization: Bearer {accessToken}
Content-Type: application/json
```

검증에서는 관리자 계정으로 로그인한 뒤 상품을 수정했습니다.

예시:

```json
{
  "name": "여름 티셔츠",
  "price": 21000,
  "comment": "통기성 좋은 티셔츠",
  "category": "FASHION"
}
```

상품 수정 후 다음 로그를 확인했습니다.

```text
[Redis] product:condition 캐시 1건 삭제
```

이후 Redis CLI에서 확인했습니다.

```redis
KEYS product:condition:*
```

결과:

```text
(empty array)
```

상품 수정 후 기존 검색 캐시가 정상적으로 삭제된 것을 확인했습니다.

---

### 6. 캐시 삭제 이후 재조회

상품 수정 후 다시 상품 목록을 조회하면:

```text
Cache MISS
```

가 발생했습니다.

```text
캐시 삭제
    ↓
Cache Miss
    ↓
DB 최신 데이터 조회
    ↓
Redis 재저장
```

이후 동일한 요청을 다시 보내면:

```text
Cache HIT
```

가 발생하는 것을 확인했습니다.

또한 수정된 상품의 조건에 맞는 조회를 수행해  
DB의 최신 상품 정보가 반영되는 것도 확인했습니다.

예:

```http
GET /products/cache?page=0&size=9&category=FASHION
```

---

## Caffeine 제거

Redis Cache-Aside 전환 이후  
더 이상 Spring Cache 추상화와 Caffeine을 사용하지 않기 때문에 기존 설정을 제거했습니다.

### `build.gradle`

제거:

```groovy
implementation 'org.springframework.boot:spring-boot-starter-cache'
implementation 'com.github.ben-manes.caffeine:caffeine'
```

Redis 의존성은 유지합니다.

```groovy
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

---

### `application.yaml`

제거:

```yaml
spring:
  cache:
    caffeine:
      spec: maximumSize=100,expireAfterWrite=300s
```

Redis 설정은 유지합니다.

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
```

---

### `CommerceplusApplication`

Spring Cache 어노테이션을 더 이상 사용하지 않으므로  
다음 설정을 제거했습니다.

```java
@EnableCaching
```

다음 설정은 기존 기능에서 사용하므로 그대로 유지했습니다.

```java
@EnableMethodSecurity
@EnableScheduling
```

---

## 한계 및 개선점

### 1. 트랜잭션과 캐시 삭제 시점

현재 `updateProduct()`에서는 트랜잭션이 실제로 커밋되기 전에  
`productCacheService.deleteAll()`이 실행될 수 있습니다.

현재 흐름:

```text
상품 데이터 변경
    ↓
Redis 캐시 삭제
    ↓
Transaction Commit
```

실제 검증 과정에서도 콘솔에서

```text
[Redis] product:condition 캐시 1건 삭제
```

로그가 Hibernate의 `UPDATE products ...` SQL보다 먼저 출력되는 것을 확인했습니다.

이는 JPA가 Entity의 변경 내용을 즉시 SQL로 실행하지 않고  
트랜잭션 종료 시점에 flush할 수 있기 때문입니다.

캐시가 삭제된 직후 아직 DB 트랜잭션이 커밋되기 전에  
다른 조회 요청이 들어오면 이전 DB 값을 조회해 Redis에 다시 저장할 가능성이 있습니다.

향후에는 다음과 같은 순서로 개선할 수 있습니다.

```text
상품 데이터 변경
    ↓
Transaction Commit
    ↓
Redis 캐시 삭제
```

예를 들어 다음 방식을 검토할 수 있습니다.

```text
TransactionSynchronization.afterCommit()
트랜잭션 이벤트 기반 캐시 무효화
```

---

### 2. `KEYS` 명령 사용

현재 `deleteAll()`은 학습 목적으로 다음 방식으로 캐시 Key를 조회합니다.

```redis
KEYS product:condition:*
```

`KEYS`는 Redis의 전체 Key Space를 탐색하므로  
Key가 많은 운영 환경에서는 Redis 응답을 지연시킬 수 있습니다.

현재 실습 환경에서는 Key 수가 적어 문제가 없지만,  
운영 환경에서는 `SCAN` 기반 탐색으로 변경할 필요가 있습니다.

---

## 결과

기존 Caffeine 기반 로컬 캐시를 Redis 기반 Cache-Aside 구조로 전환하면서  
다음 흐름을 직접 구현하고 검증했습니다.

```text
첫 조회
→ Cache Miss
→ DB 조회
→ Redis 저장
→ TTL 적용

동일 조건 재조회
→ Cache Hit
→ DB 조회 생략

상품 수정
→ Redis 캐시 무효화

수정 후 재조회
→ Cache Miss
→ DB 최신 데이터 조회
→ Redis 재저장

다시 조회
→ Cache Hit
```

Spring Cache 어노테이션이 대신 처리하던 캐시 흐름을  
`RedisTemplate`을 통해 직접 구현하면서  
Redis의 조회, 저장, TTL, Cache Hit/Miss, 캐시 무효화 과정을 확인했습니다.

또한 실제 동작 검증 과정에서  
트랜잭션 커밋과 캐시 삭제 시점의 차이, `KEYS` 명령의 운영상 한계도 확인했습니다.