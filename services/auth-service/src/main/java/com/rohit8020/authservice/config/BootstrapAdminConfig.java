package com.rohit8020.authservice.config;

import com.rohit8020.authservice.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BootstrapAdminConfig {

    @Bean
    public ApplicationRunner bootstrapAdmin(AuthService authService,
                                            @Value("${app.bootstrap-admin.username}") String username,
                                            @Value("${app.bootstrap-admin.password}") String password) {
        return args -> authService.ensureBootstrapAdmin(username, password);
    }
}
