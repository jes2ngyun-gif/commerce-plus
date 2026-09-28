package com.example.commerceplus.domain.product.dto.condition;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

// Redis 캐시 전용 DTO
// - record가 아닌 class인 이유: RedisConfig의 DefaultTyping.NON_FINAL은 final이 아닌 타입에만 "@class"를 기록한다.
//   record는 암묵적으로 final이라 타입 정보 없이 저장되고, 꺼낼 때 LinkedHashMap으로 복원되어 ClassCastException이 난다.
// - Page(PageImpl)를 직접 저장하지 않는 이유: Jackson이 PageImpl을 역직렬화할 수 없다.
//   → 알맹이(content, page, size, totalElements)만 저장하고 꺼낼 때 PageImpl로 다시 조립한다.
@Getter
@NoArgsConstructor   // Jackson 역직렬화용 기본 생성자
@AllArgsConstructor
public class ProductConditionCache {

    private List<SearchProductConditionResponse> content;
    private int page;
    private int size;
    private long totalElements;

    // Page → 캐시 DTO (저장할 때)
    public static ProductConditionCache from(Page<SearchProductConditionResponse> pageResult) {
        return new ProductConditionCache(
                pageResult.getContent(),
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements()
        );
    }

    // 캐시 DTO → Page (꺼낼 때)
    public Page<SearchProductConditionResponse> toPage() {
        return new PageImpl<>(content, PageRequest.of(page, size), totalElements);
    }
}