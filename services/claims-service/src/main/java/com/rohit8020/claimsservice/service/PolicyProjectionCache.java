package com.rohit8020.claimsservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit8020.claimsservice.entity.PolicyProjection;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PolicyProjectionCache {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PolicyProjectionCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<PolicyProjection> get(String policyId) {
        String raw = redisTemplate.opsForValue().get(key(policyId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, PolicyProjection.class));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public void put(PolicyProjection projection) {
        try {
            redisTemplate.opsForValue()
                    .set(key(projection.getId()), objectMapper.writeValueAsString(projection), Duration.ofHours(6));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to cache policy projection", ex);
        }
    }

    private String key(String policyId) {
        return "policy-projection:" + policyId;
    }
}
