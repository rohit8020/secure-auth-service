package com.rohit8020.claimsservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit8020.claimsservice.entity.PolicyProjection;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PolicyProjectionCacheTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private PolicyProjectionCache cache;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new PolicyProjectionCache(redisTemplate, objectMapper);
    }

    @Test
    void getReturnsEmptyWhenCacheMisses() {
        when(valueOperations.get("policy-projection:policy-1")).thenReturn(null);

        assertThat(cache.get("policy-1")).isEmpty();
    }

    @Test
    void getReturnsProjectionWhenJsonIsValid() throws Exception {
        PolicyProjection projection = projection();
        when(valueOperations.get("policy-projection:policy-1"))
                .thenReturn(objectMapper.writeValueAsString(projection));

        assertThat(cache.get("policy-1"))
                .hasValueSatisfying(cached -> {
                    assertThat(cached.getId()).isEqualTo("policy-1");
                    assertThat(cached.getPolicyholderId()).isEqualTo(7L);
                    assertThat(cached.getAssignedAgentId()).isEqualTo(99L);
                    assertThat(cached.getStatus()).isEqualTo("ISSUED");
                    assertThat(cached.isClaimable()).isTrue();
                });
    }

    @Test
    void getReturnsEmptyWhenJsonIsInvalid() {
        when(valueOperations.get("policy-projection:policy-1")).thenReturn("not-json");

        assertThat(cache.get("policy-1")).isEmpty();
    }

    @Test
    void putStoresSerializedProjection() throws Exception {
        PolicyProjection projection = projection();

        cache.put(projection);

        verify(valueOperations).set(
                eq("policy-projection:policy-1"),
                eq(objectMapper.writeValueAsString(projection)),
                eq(Duration.ofHours(6))
        );
    }

    @Test
    void putWrapsSerializationFailure() {
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        PolicyProjectionCache failingCache = new PolicyProjectionCache(redisTemplate, failingMapper);
        try {
            when(failingMapper.writeValueAsString(org.mockito.Mockito.any()))
                    .thenThrow(new JsonProcessingException("boom") { });
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }

        assertThatThrownBy(() -> failingCache.put(projection()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache policy projection");
    }

    private PolicyProjection projection() {
        PolicyProjection projection = new PolicyProjection();
        projection.setId("policy-1");
        projection.setPolicyholderId(7L);
        projection.setAssignedAgentId(99L);
        projection.setStatus("ISSUED");
        projection.setClaimable(true);
        projection.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return projection;
    }
}
