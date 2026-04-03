package com.rohit8020.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class IdentityHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .flatMap(authentication -> {
                    Object userId = authentication.getToken().getClaims().get("userId");
                    ServerWebExchange mutated = exchange.mutate()
                            .request(builder -> builder
                                    .header("X-User-Id", userId == null ? "" : userId.toString())
                                    .header("X-Username", authentication.getToken().getSubject())
                                    .header("X-User-Role", authentication.getToken().getClaimAsString("role")))
                            .build();
                    return chain.filter(mutated);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        return -10;
    }
}
