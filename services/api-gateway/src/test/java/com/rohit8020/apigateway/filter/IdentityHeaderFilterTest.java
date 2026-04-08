package com.rohit8020.apigateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class IdentityHeaderFilterTest {

    private final IdentityHeaderFilter filter = new IdentityHeaderFilter();

    @Test
    void filterAddsIdentityHeadersFromJwtClaims() {
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.get("/api/policies").build())
                .principal(jwt(7L, "holder", "POLICYHOLDER"))
                .build();
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        GatewayFilterChain chain = chainedExchange -> {
            seen.set(chainedExchange);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("7");
        assertThat(seen.get().getRequest().getHeaders().getFirst("X-Username")).isEqualTo("holder");
        assertThat(seen.get().getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("POLICYHOLDER");
    }

    @Test
    void filterUsesEmptyHeaderWhenUserIdClaimIsMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("admin")
                .claim("role", "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.get("/api/admin").build())
                .principal(new JwtAuthenticationToken(jwt))
                .build();
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        filter.filter(exchange, chainedExchange -> {
            seen.set(chainedExchange);
            return Mono.empty();
        }).block();

        assertThat(seen.get().getRequest().getHeaders().getFirst("X-User-Id")).isEmpty();
        assertThat(seen.get().getRequest().getHeaders().getFirst("X-Username")).isEqualTo("admin");
        assertThat(seen.get().getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("ADMIN");
    }

    @Test
    void filterLeavesRequestUnchangedWhenPrincipalIsMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/public"));
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        filter.filter(exchange, chainedExchange -> {
            seen.set(chainedExchange);
            return Mono.empty();
        }).block();

        assertThat(seen.get()).isSameAs(exchange);
        assertThat(seen.get().getRequest().getHeaders().containsKey("X-User-Id")).isFalse();
    }

    @Test
    void getOrderReturnsConfiguredPriority() {
        assertThat(filter.getOrder()).isEqualTo(-10);
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
