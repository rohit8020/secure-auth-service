package com.rohit8020.authservice.service;

import com.rohit8020.authservice.dto.AuthResponse;
import com.rohit8020.authservice.dto.CreateUserRequest;
import com.rohit8020.authservice.dto.LoginRequest;
import com.rohit8020.authservice.dto.RegisterRequest;
import com.rohit8020.authservice.dto.RefreshTokenRequest;
import com.rohit8020.authservice.dto.UserResponse;
import com.rohit8020.authservice.dto.ClientCredentialsTokenResponse;
import com.rohit8020.authservice.config.MachineClientProperties;
import com.rohit8020.authservice.entity.RefreshToken;
import com.rohit8020.authservice.entity.User;
import com.rohit8020.authservice.entity.UserRole;
import com.rohit8020.authservice.exception.ApiException;
import com.rohit8020.authservice.repository.RefreshTokenRepository;
import com.rohit8020.authservice.repository.UserRepository;
import com.rohit8020.authservice.security.JwtUtil;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final MachineClientProperties machineClientProperties;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       MachineClientProperties machineClientProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.machineClientProperties = machineClientProperties;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = createUserInternal(request.username(), request.password(), UserRole.POLICYHOLDER);
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(
                request.password(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(hashToken(request.refreshToken()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        refreshToken.setRevoked(true);
        return issueTokens(refreshToken.getUser());
    }

    public ClientCredentialsTokenResponse issueClientCredentialsToken(String clientId,
                                                                      String clientSecret,
                                                                      String requestedScope) {
        MachineClientProperties.Client client = machineClientProperties.clients().stream()
                .filter(candidate -> candidate.clientId().equals(clientId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid client credentials"));

        if (!constantTimeEquals(client.clientSecret(), clientSecret)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid client credentials");
        }

        String scope = resolveScope(client.scopes(), requestedScope);
        String accessToken = jwtUtil.generateClientToken(clientId, scope);
        return new ClientCredentialsTokenResponse(accessToken, "Bearer",
                jwtUtil.getClientAccessExpiration() / 1000, scope);
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        User user = createUserInternal(request.username(), request.password(), request.role());
        return mapUser(user);
    }

    public List<UserResponse> listUsers() {
        return userRepository.findAllByOrderByIdAsc().stream()
                .map(this::mapUser)
                .toList();
    }

    public UserResponse currentUser(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        User user = userRepository.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return mapUser(user);
    }

    @Transactional
    public void ensureBootstrapAdmin(String username, String password) {
        if (userRepository.countByRole(UserRole.ADMIN) > 0) {
            return;
        }
        createUserInternal(username, password, UserRole.ADMIN);
    }

    private User createUserInternal(String username, String rawPassword, UserRole role) {
        if (userRepository.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "Username already exists");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        return userRepository.save(user);
    }

    private AuthResponse issueTokens(User user) {
        refreshTokenRepository.findAllByUserIdAndRevokedFalse(user.getId())
                .forEach(token -> token.setRevoked(true));

        String accessToken = jwtUtil.generateToken(user);
        String rawRefreshToken = UUID.randomUUID() + "." + UUID.randomUUID();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setTokenHash(hashToken(rawRefreshToken));
        refreshToken.setUser(user);
        refreshToken.setExpiresAt(jwtUtil.refreshTokenExpiry().toInstant());
        refreshToken.setRevoked(false);
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                "Bearer",
                jwtUtil.getAccessExpiration(),
                user.getId(),
                user.getUsername(),
                user.getRole().name()
        );
    }

    private UserResponse mapUser(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole().name());
    }

    private String resolveScope(List<String> allowedScopes, String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return String.join(" ", allowedScopes);
        }

        List<String> requested = List.of(requestedScope.trim().split("\\s+"));
        if (!allowedScopes.containsAll(requested)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid scope");
        }
        return String.join(" ", requested);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash token", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
