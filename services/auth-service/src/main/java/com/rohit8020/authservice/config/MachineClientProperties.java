package com.rohit8020.authservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.service-auth")
public record MachineClientProperties(
        @NotEmpty List<Client> clients
) {
    public record Client(
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotEmpty List<String> scopes
    ) {
    }
}
