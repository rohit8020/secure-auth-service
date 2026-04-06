package com.rohit8020.authservice.controller;

import com.rohit8020.authservice.dto.ClientCredentialsTokenResponse;
import com.rohit8020.authservice.exception.ApiException;
import com.rohit8020.authservice.security.JwtUtil;
import com.rohit8020.authservice.service.AuthService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    public OAuthController(AuthService authService, JwtUtil jwtUtil) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<ClientCredentialsTokenResponse> token(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody MultiValueMap<String, String> form) {

        String grantType = form.getFirst("grant_type");
        if (!"client_credentials".equals(grantType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported grant_type");
        }

        String clientId = form.getFirst("client_id");
        String clientSecret = form.getFirst("client_secret");
        if (authorization != null && authorization.startsWith("Basic ")) {
            String[] credentials = new String(java.util.Base64.getDecoder()
                    .decode(authorization.substring("Basic ".length())))
                    .split(":", 2);
            clientId = credentials[0];
            clientSecret = credentials.length > 1 ? credentials[1] : "";
        }

        return ResponseEntity.ok(authService.issueClientCredentialsToken(
                clientId,
                clientSecret,
                form.getFirst("scope")));
    }

    @GetMapping({"/oauth2/jwks", "/.well-known/jwks.json"})
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok(jwtUtil.jwkSet().toJSONObject());
    }
}
