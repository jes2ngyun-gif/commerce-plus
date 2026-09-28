package com.example.commerceplus.domain.product.service;

import com.example.commerceplus.domain.product.dto.condition.ProductConditionCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Log4j2
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    // 키 규칙: product:condition:{page}:{size}:{minPrice}:{maxPrice}:{category}
    private static final String CACHE_PREFIX = "product:condition:";
    private static final long TTL_MINUTES = 5;   // 기존 Caffeine 설정(expireAfterWrite=300s)과 동일

    // 캐시에 저장  (redis-cli: SET key value EX 300)
    public void save(String cacheKey, ProductConditionCache data) {
        String key = CACHE_PREFIX + cacheKey;
        redisTemplate.opsForValue().set(key, data, TTL_MINUTES, TimeUnit.MINUTES);
    }

    // 캐시에서 조회  (redis-cli: GET key)  → 없으면 null = Cache Miss
    public ProductConditionCache get(String cacheKey) {
        String key = CACHE_PREFIX + cacheKey;
        return (ProductConditionCache) redisTemplate.opsForValue().get(key);
    }

    // 상품 조건 캐시 전체 삭제  (기존 @CacheEvict(allEntries = true) 와 동일한 역할)
    public void deleteAll() {
        Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*");   // redis-cli: KEYS product:condition:*
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);                              // redis-cli: DEL key1 key2 ...
            log.info("[Redis] product:condition 캐시 {}건 삭제", keys.size());
        }
    }
}