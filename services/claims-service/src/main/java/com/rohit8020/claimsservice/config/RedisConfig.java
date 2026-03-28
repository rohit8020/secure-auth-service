package com.rohit8020.claimsservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@Configuration
@EnableRedisRepositories(basePackages = "com.rohit8020.claimsservice.repository")
public class RedisConfig {
}
