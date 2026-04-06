package com.rohit8020.authservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClientCredentialsTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("scope") String scope
) {
}
