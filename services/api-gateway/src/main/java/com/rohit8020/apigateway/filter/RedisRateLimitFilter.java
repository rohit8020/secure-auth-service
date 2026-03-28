package com.rohit8020.apigateway.filter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RedisRateLimitFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final long loginLimit;
    private final long claimSubmitLimit;

    public RedisRateLimitFilter(ReactiveStringRedisTemplate redisTemplate,
                                @Value("${app.rate-limit.login-per-minute}") long loginLimit,
                                @Value("${app.rate-limit.claim-submit-per-minute}") long claimSubmitLimit) {
        this.redisTemplate = redisTemplate;
        this.loginLimit = loginLimit;
        this.claimSubmitLimit = claimSubmitLimit;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();

        if (HttpMethod.POST.equals(method) && "/api/auth/login".equals(path)) {
            String key = "rate:login:" + exchange.getRequest().getRemoteAddress();
            return enforce(exchange, chain, key, loginLimit);
        }

        if (HttpMethod.POST.equals(method) && "/api/claims".equals(path)) {
            return exchange.getPrincipal()
                    .cast(JwtAuthenticationToken.class)
                    .map(authentication -> "rate:claims:" + authentication.getToken().getClaim("userId"))
                    .defaultIfEmpty("rate:claims:" + exchange.getRequest().getRemoteAddress())
                    .flatMap(key -> enforce(exchange, chain, key, claimSubmitLimit));
        }

        return chain.filter(exchange);
    }

    private Mono<Void> enforce(ServerWebExchange exchange,
                               GatewayFilterChain chain,
                               String key,
                               long limit) {
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    Mono<Boolean> expiry = count == 1
                            ? redisTemplate.expire(key, Duration.ofMinutes(1))
                            : Mono.just(Boolean.TRUE);
                    return expiry.thenReturn(count);
                })
                .flatMap(count -> {
                    if (count > limit) {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        byte[] payload = "{\"error\":\"Rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
                        return exchange.getResponse().writeWith(
                                Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return -20;
    }
}
