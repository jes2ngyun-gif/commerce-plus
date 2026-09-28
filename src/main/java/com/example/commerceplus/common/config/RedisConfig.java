package com.example.commerceplus.common.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory); // application.yaml의 spring.data.redis 설정으로 만들어진 연결

        // ObjectMapper 커스터마이징
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())                      // ① LocalDate/LocalDateTime 직렬화 지원
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)  // ② 날짜를 배열 대신 "2026-09-28T10:00:00" 문자열로
                .activateDefaultTyping(                                    // ③ JSON에 "@class" 타입 정보 포함 → 꺼낼 때 원래 타입으로 복원
                        LaissezFaireSubTypeValidator.instance,
                        ObjectMapper.DefaultTyping.NON_FINAL,
                        JsonTypeInfo.As.PROPERTY
                );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        template.setKeySerializer(new StringRedisSerializer());     // 키: 사람이 읽는 문자열
        template.setValueSerializer(serializer);                    // 값: JSON

        template.setHashKeySerializer(new StringRedisSerializer()); // Hash 자료형 필드명
        template.setHashValueSerializer(serializer);                // Hash 자료형 값

        template.afterPropertiesSet();
        return template;
    }
}