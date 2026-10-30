package com.weatherplatform.authservice.dto;

import com.weatherplatform.authservice.entity.UserRole;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class UserResponse {
    Long id;
    String name;
    String email;
    UserRole role;
    LocalDateTime createdAt;
}
