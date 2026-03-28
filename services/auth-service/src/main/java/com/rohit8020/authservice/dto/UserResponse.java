package com.rohit8020.authservice.dto;

public record UserResponse(
        Long id,
        String username,
        String role
) {
}
