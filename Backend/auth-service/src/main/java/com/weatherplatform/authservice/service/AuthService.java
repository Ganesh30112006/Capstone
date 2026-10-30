package com.weatherplatform.authservice.service;

import com.weatherplatform.authservice.dto.LoginRequest;
import com.weatherplatform.authservice.dto.LoginResponse;
import com.weatherplatform.authservice.dto.RegisterRequest;
import com.weatherplatform.authservice.dto.UserResponse;

public interface AuthService {
    UserResponse register(RegisterRequest request);
    LoginResponse login(LoginRequest request);
    UserResponse getCurrentUser(Long userId);
}
