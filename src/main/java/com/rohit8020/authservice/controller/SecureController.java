package com.rohit8020.authservice.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/secure")
public class SecureController {

    @GetMapping("/user")
    public String userEndpoint() {
        return "User authenticated";
    }

    @GetMapping("/admin")
    public String adminEndpoint() {
        return "Admin authenticated";
    }
}
