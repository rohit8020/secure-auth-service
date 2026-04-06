package com.rohit8020.authservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.rohit8020.authservice.dto.AuthResponse;
import com.rohit8020.authservice.dto.RefreshTokenRequest;
import com.rohit8020.authservice.dto.RegisterRequest;
import com.rohit8020.authservice.entity.RefreshToken;
import com.rohit8020.authservice.repository.RefreshTokenRepository;
import com.rohit8020.authservice.service.AuthService;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void refreshTokensAreStoredHashedAndRotateOnRefresh() {
        AuthResponse registered = authService.register(new RegisterRequest(
                "policyholder_" + System.nanoTime(), "Policy@12345"));

        RefreshToken stored = refreshTokenRepository.findAll().stream()
                .max(Comparator.comparing(RefreshToken::getId))
                .orElseThrow();

        assertThat(stored.getTokenHash()).hasSize(64);
        assertThat(stored.getTokenHash()).isNotEqualTo(registered.refreshToken());
        assertThat(stored.isRevoked()).isFalse();

        AuthResponse refreshed = authService.refresh(new RefreshTokenRequest(registered.refreshToken()));

        RefreshToken original = refreshTokenRepository.findById(stored.getId()).orElseThrow();
        assertThat(original.isRevoked()).isTrue();
        assertThat(refreshed.refreshToken()).isNotEqualTo(registered.refreshToken());
        assertThat(refreshTokenRepository.findAll()).hasSize(2);
        assertThat(refreshTokenRepository.findByTokenHash(original.getTokenHash())).isPresent();
    }
}
