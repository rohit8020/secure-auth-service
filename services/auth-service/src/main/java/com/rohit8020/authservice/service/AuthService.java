package com.rohit8020.authservice.service;

import com.rohit8020.authservice.dto.AuthResponse;
import com.rohit8020.authservice.dto.CreateUserRequest;
import com.rohit8020.authservice.dto.LoginRequest;
import com.rohit8020.authservice.dto.RegisterRequest;
import com.rohit8020.authservice.dto.RefreshTokenRequest;
import com.rohit8020.authservice.dto.UserResponse;
import com.rohit8020.authservice.entity.RefreshToken;
import com.rohit8020.authservice.entity.User;
import com.rohit8020.authservice.entity.UserRole;
import com.rohit8020.authservice.exception.ApiException;
import com.rohit8020.authservice.repository.RefreshTokenRepository;
import com.rohit8020.authservice.repository.UserRepository;
import com.rohit8020.authservice.security.JwtUtil;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
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

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
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
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        refreshToken.setRevoked(true);
        return issueTokens(refreshToken.getUser());
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

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(UUID.randomUUID().toString());
        refreshToken.setUser(user);
        refreshToken.setExpiresAt(jwtUtil.refreshTokenExpiry().toInstant());
        refreshToken.setRevoked(false);
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                refreshToken.getToken(),
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
}
