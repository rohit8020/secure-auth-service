package com.rohit8020.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rohit8020.authservice.config.MachineClientProperties;
import com.rohit8020.authservice.dto.AuthResponse;
import com.rohit8020.authservice.dto.ClientCredentialsTokenResponse;
import com.rohit8020.authservice.dto.CreateUserRequest;
import com.rohit8020.authservice.dto.LoginRequest;
import com.rohit8020.authservice.dto.RefreshTokenRequest;
import com.rohit8020.authservice.dto.RegisterRequest;
import com.rohit8020.authservice.dto.UserResponse;
import com.rohit8020.authservice.entity.RefreshToken;
import com.rohit8020.authservice.entity.User;
import com.rohit8020.authservice.entity.UserRole;
import com.rohit8020.authservice.exception.ApiException;
import com.rohit8020.authservice.repository.RefreshTokenRepository;
import com.rohit8020.authservice.repository.UserRepository;
import com.rohit8020.authservice.security.JwtUtil;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        MachineClientProperties properties = new MachineClientProperties(List.of(
                new MachineClientProperties.Client("claims-service", "claims-secret",
                        List.of("policy.projection.read", "claims.write"))
        ));
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtUtil, properties);
    }

    @Test
    void registerCreatesPolicyholderAndIssuesTokens() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("Password@123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(11L);
            return user;
        });
        RefreshToken existing = refreshToken(1L, user(11L, "alice", UserRole.POLICYHOLDER), Instant.now().plusSeconds(60), false);
        when(refreshTokenRepository.findAllByUserIdAndRevokedFalse(11L)).thenReturn(List.of(existing));
        when(jwtUtil.generateToken(any(User.class))).thenReturn("access-token");
        when(jwtUtil.refreshTokenExpiry()).thenReturn(Date.from(Instant.parse("2030-01-01T00:00:00Z")));
        when(jwtUtil.getAccessExpiration()).thenReturn(3600L);

        AuthResponse response = authService.register(new RegisterRequest("alice", "Password@123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.userId()).isEqualTo(11L);
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.role()).isEqualTo(UserRole.POLICYHOLDER.name());
        assertThat(existing.isRevoked()).isTrue();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("alice");
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded");
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.POLICYHOLDER);

        ArgumentCaptor<RefreshToken> refreshCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(refreshCaptor.capture());
        RefreshToken savedRefresh = refreshCaptor.getValue();
        assertThat(savedRefresh.getTokenHash()).hasSize(64);
        assertThat(savedRefresh.isRevoked()).isFalse();
        assertThat(savedRefresh.getExpiresAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(savedRefresh.getUser().getId()).isEqualTo(11L);
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice", "Password@123")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);

        verifyNoInteractions(refreshTokenRepository, passwordEncoder, jwtUtil);
    }

    @Test
    void loginIssuesTokensWhenCredentialsMatch() {
        User user = user(15L, "alice", UserRole.ADMIN);
        user.setPassword("encoded-password");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password@123", "encoded-password")).thenReturn(true);
        when(refreshTokenRepository.findAllByUserIdAndRevokedFalse(15L)).thenReturn(List.of());
        when(jwtUtil.generateToken(user)).thenReturn("login-token");
        when(jwtUtil.refreshTokenExpiry()).thenReturn(Date.from(Instant.parse("2031-01-01T00:00:00Z")));
        when(jwtUtil.getAccessExpiration()).thenReturn(7200L);

        AuthResponse response = authService.login(new LoginRequest("alice", "Password@123"));

        assertThat(response.accessToken()).isEqualTo("login-token");
        assertThat(response.role()).isEqualTo(UserRole.ADMIN.name());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void loginRejectsUnknownUser() {
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("missing", "Password@123")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = user(15L, "alice", UserRole.ADMIN);
        user.setPassword("encoded-password");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRotatesTokenWhenActive() {
        User user = user(23L, "alice", UserRole.POLICYHOLDER);
        RefreshToken active = refreshToken(99L, user, Instant.now().plusSeconds(300), false);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(active));
        when(refreshTokenRepository.findAllByUserIdAndRevokedFalse(23L)).thenReturn(List.of(active));
        when(jwtUtil.generateToken(user)).thenReturn("refreshed-access");
        when(jwtUtil.refreshTokenExpiry()).thenReturn(Date.from(Instant.parse("2032-01-01T00:00:00Z")));
        when(jwtUtil.getAccessExpiration()).thenReturn(1200L);

        AuthResponse response = authService.refresh(new RefreshTokenRequest("raw-refresh-token"));

        assertThat(response.accessToken()).isEqualTo("refreshed-access");
        assertThat(active.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshRejectsUnknownRefreshToken() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("unknown")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRejectsRevokedToken() {
        RefreshToken revoked = refreshToken(99L, user(23L, "alice", UserRole.POLICYHOLDER), Instant.now().plusSeconds(300), true);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("revoked")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRejectsExpiredToken() {
        RefreshToken expired = refreshToken(99L, user(23L, "alice", UserRole.POLICYHOLDER), Instant.now().minusSeconds(1), false);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("expired")))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void issueClientCredentialsTokenUsesRequestedScope() {
        when(jwtUtil.generateClientToken("claims-service", "claims.write")).thenReturn("client-token");
        when(jwtUtil.getClientAccessExpiration()).thenReturn(300_000L);

        ClientCredentialsTokenResponse response = authService.issueClientCredentialsToken(
                "claims-service", "claims-secret", "claims.write");

        assertThat(response.accessToken()).isEqualTo("client-token");
        assertThat(response.expiresIn()).isEqualTo(300L);
        assertThat(response.scope()).isEqualTo("claims.write");
    }

    @Test
    void issueClientCredentialsTokenUsesAllScopesWhenRequestedScopeBlank() {
        when(jwtUtil.generateClientToken("claims-service", "policy.projection.read claims.write"))
                .thenReturn("client-token");
        when(jwtUtil.getClientAccessExpiration()).thenReturn(300_000L);

        ClientCredentialsTokenResponse response = authService.issueClientCredentialsToken(
                "claims-service", "claims-secret", " ");

        assertThat(response.scope()).isEqualTo("policy.projection.read claims.write");
    }

    @Test
    void issueClientCredentialsTokenRejectsUnknownClient() {
        assertThatThrownBy(() -> authService.issueClientCredentialsToken("missing", "secret", null))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void issueClientCredentialsTokenRejectsInvalidSecret() {
        assertThatThrownBy(() -> authService.issueClientCredentialsToken("claims-service", "wrong-secret", null))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void issueClientCredentialsTokenRejectsInvalidScope() {
        assertThatThrownBy(() -> authService.issueClientCredentialsToken(
                "claims-service", "claims-secret", "admin"))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createUserCreatesRequestedRole() {
        when(userRepository.existsByUsername("agent")).thenReturn(false);
        when(passwordEncoder.encode("Password@123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(45L);
            return user;
        });

        UserResponse response = authService.createUser(new CreateUserRequest("agent", "Password@123", UserRole.AGENT));

        assertThat(response).isEqualTo(new UserResponse(45L, "agent", UserRole.AGENT.name()));
    }

    @Test
    void listUsersMapsResults() {
        when(userRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                user(1L, "admin", UserRole.ADMIN),
                user(2L, "agent", UserRole.AGENT)
        ));

        assertThat(authService.listUsers()).containsExactly(
                new UserResponse(1L, "admin", UserRole.ADMIN.name()),
                new UserResponse(2L, "agent", UserRole.AGENT.name())
        );
    }

    @Test
    void currentUserReturnsMappedUser() {
        Authentication authentication = authentication("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(9L, "alice", UserRole.POLICYHOLDER)));

        UserResponse response = authService.currentUser(authentication);

        assertThat(response).isEqualTo(new UserResponse(9L, "alice", UserRole.POLICYHOLDER.name()));
    }

    @Test
    void currentUserRejectsMissingUser() {
        Authentication authentication = authentication("missing");
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.currentUser(authentication))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ensureBootstrapAdminSkipsCreationWhenAdminExists() {
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L);

        authService.ensureBootstrapAdmin("admin", "Password@123");

        verify(userRepository).countByRole(UserRole.ADMIN);
        verifyNoInteractions(passwordEncoder, refreshTokenRepository, jwtUtil);
    }

    @Test
    void ensureBootstrapAdminCreatesAdminWhenMissing() {
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(0L);
        when(userRepository.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("Password@123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(99L);
            return user;
        });

        authService.ensureBootstrapAdmin("admin", "Password@123");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded");
    }

    private Authentication authentication(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .build();
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(jwt);
        return authentication;
    }

    private User user(Long id, String username, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }

    private RefreshToken refreshToken(Long id, User user, Instant expiresAt, boolean revoked) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setId(id);
        refreshToken.setUser(user);
        refreshToken.setExpiresAt(expiresAt);
        refreshToken.setRevoked(revoked);
        refreshToken.setTokenHash("a".repeat(64));
        return refreshToken;
    }
}
