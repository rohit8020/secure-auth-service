package com.rohit8020.authservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rohit8020.authservice.dto.CreateUserRequest;
import com.rohit8020.authservice.dto.UserResponse;
import com.rohit8020.authservice.entity.UserRole;
import com.rohit8020.authservice.service.AuthService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock
    private AuthService authService;

    @Test
    void createUserDelegatesToService() {
        AdminUserController controller = new AdminUserController(authService);
        CreateUserRequest request = new CreateUserRequest("agent", "Password@123", UserRole.AGENT);
        UserResponse response = new UserResponse(2L, "agent", "AGENT");
        when(authService.createUser(request)).thenReturn(response);

        assertThat(controller.createUser(request).getBody()).isEqualTo(response);
        verify(authService).createUser(request);
    }

    @Test
    void listUsersDelegatesToService() {
        AdminUserController controller = new AdminUserController(authService);
        List<UserResponse> response = List.of(
                new UserResponse(1L, "admin", "ADMIN"),
                new UserResponse(2L, "agent", "AGENT")
        );
        when(authService.listUsers()).thenReturn(response);

        assertThat(controller.listUsers().getBody()).isEqualTo(response);
        verify(authService).listUsers();
    }
}
