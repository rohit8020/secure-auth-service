package com.rohit8020.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RedisRateLimitFilterTest {

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private GatewayFilterChain chain;

    private RedisRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RedisRateLimitFilter(redisTemplate, 10, 2);
    }

    @Test
    void filterAllowsLoginRequestWithinLimitAndSetsExpiryOnFirstHit() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        String key = "rate:login:" + request.getRemoteAddress();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(key, Duration.ofMinutes(1))).thenReturn(Mono.just(Boolean.TRUE));
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verify(redisTemplate).expire(key, Duration.ofMinutes(1));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void filterRejectsLoginRequestWhenLimitIsExceeded() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        String key = "rate:login:" + request.getRemoteAddress();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenReturn(Mono.just(11L));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        verify(redisTemplate, never()).expire(any(), any(Duration.class));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("{\"error\":\"Rate limit exceeded\"}");
    }

    @Test
    void filterUsesJwtUserIdForClaimSubmissionRateLimitKey() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/claims")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(request)
                .principal(jwt(7L, "holder", "POLICYHOLDER"))
                .build();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("rate:claims:7")).thenReturn(Mono.just(1L));
        when(redisTemplate.expire("rate:claims:7", Duration.ofMinutes(1))).thenReturn(Mono.just(Boolean.TRUE));
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(valueOperations).increment("rate:claims:7");
        verify(redisTemplate).expire("rate:claims:7", Duration.ofMinutes(1));
        verify(chain).filter(exchange);
    }

    @Test
    void filterFallsBackToRemoteAddressForAnonymousClaimSubmission() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/claims")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        String key = "rate:claims:" + request.getRemoteAddress();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(key)).thenReturn(Mono.just(2L));
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(valueOperations).increment(key);
        verify(redisTemplate, never()).expire(any(), any(Duration.class));
        verify(chain).filter(exchange);
    }

    @Test
    void filterBypassesUnrelatedRoutes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/policies"));
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(valueOperations);
    }

    @Test
    void getOrderReturnsConfiguredPriority() {
        assertThat(filter.getOrder()).isEqualTo(-20);
    }

    private JwtAuthenticationToken jwt(Long userId, String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("userId", userId)
                .claim("role", role)
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
