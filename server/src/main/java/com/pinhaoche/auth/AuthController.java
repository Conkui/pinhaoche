package com.pinhaoche.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录接口（openapi: POST /auth/login，RFC-002）。 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(@NotBlank String code) {
    }

    @PostMapping("/auth/login")
    public AuthService.LoginResult login(@RequestBody LoginRequest request) {
        return authService.login(request.code());
    }
}
