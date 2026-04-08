package com.rohit8020.authservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rohit8020.authservice.dto.AuthResponse;
import com.rohit8020.authservice.dto.LoginRequest;
import com.rohit8020.authservice.dto.RefreshTokenRequest;
import com.rohit8020.authservice.dto.RegisterRequest;
import com.rohit8020.authservice.dto.UserResponse;
import com.rohit8020.authservice.service.AuthService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private Authentication authentication;

    @Test
    void healthReturnsUpPayload() {
        AuthController controller = new AuthController(authService);

        assertThat(controller.health().getBody()).isEqualTo(Map.of("service", "auth-service", "status", "UP"));
    }

    @Test
    void registerDelegatesToService() {
        AuthController controller = new AuthController(authService);
        RegisterRequest request = new RegisterRequest("alice", "Password@123");
        AuthResponse response = response();
        when(authService.register(request)).thenReturn(response);

        assertThat(controller.register(request).getBody()).isEqualTo(response);
        verify(authService).register(request);
    }

    @Test
    void loginDelegatesToService() {
        AuthController controller = new AuthController(authService);
        LoginRequest request = new LoginRequest("alice", "Password@123");
        AuthResponse response = response();
        when(authService.login(request)).thenReturn(response);

        assertThat(controller.login(request).getBody()).isEqualTo(response);
        verify(authService).login(request);
    }

    @Test
    void refreshDelegatesToService() {
        AuthController controller = new AuthController(authService);
        RefreshTokenRequest request = new RefreshTokenRequest("refresh-token");
        AuthResponse response = response();
        when(authService.refresh(request)).thenReturn(response);

        assertThat(controller.refresh(request).getBody()).isEqualTo(response);
        verify(authService).refresh(request);
    }

    @Test
    void meDelegatesToService() {
        AuthController controller = new AuthController(authService);
        UserResponse response = new UserResponse(1L, "alice", "ADMIN");
        when(authService.currentUser(authentication)).thenReturn(response);

        assertThat(controller.me(authentication).getBody()).isEqualTo(response);
        verify(authService).currentUser(authentication);
    }

    private AuthResponse response() {
        return new AuthResponse("access", "refresh", "Bearer", 3600, 1L, "alice", "ADMIN");
    }
}
