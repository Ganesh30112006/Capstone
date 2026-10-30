package com.weatherplatform.authservice.controller;

import com.weatherplatform.authservice.dto.ApiResponse;
import com.weatherplatform.authservice.dto.LoginRequest;
import com.weatherplatform.authservice.dto.LoginResponse;
import com.weatherplatform.authservice.dto.RegisterRequest;
import com.weatherplatform.authservice.dto.UserResponse;
import com.weatherplatform.authservice.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User registration, login, and profile endpoints")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register User", description = "Create a new user account with email and password")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        log.info("POST /api/auth/register — email: {}", request.getEmail());
        UserResponse user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<UserResponse>builder()
                .success(true)
                .message("User registered successfully")
                .data(user)
                .path("/api/auth/register")
                .build());
    }

    @Operation(summary = "Login", description = "Authenticate with email/password and receive a JWT token")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        log.info("POST /api/auth/login — email: {}", request.getEmail());
        LoginResponse loginResponse = authService.login(request);
        return ResponseEntity.ok(ApiResponse.<LoginResponse>builder()
                .success(true)
                .message("Login successful")
                .data(loginResponse)
                .path("/api/auth/login")
                .build());
    }

    @Operation(summary = "Get Current User", description = "Retrieve the profile of the currently authenticated user")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        log.info("GET /api/auth/me — userId: {}", userId);
        UserResponse user = authService.getCurrentUser(userId);
        return ResponseEntity.ok(ApiResponse.<UserResponse>builder()
                .success(true)
                .message("User profile retrieved successfully")
                .data(user)
                .path("/api/auth/me")
                .build());
    }
}
