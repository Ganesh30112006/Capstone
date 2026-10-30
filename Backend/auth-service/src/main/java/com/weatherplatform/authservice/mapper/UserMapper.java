package com.weatherplatform.authservice.mapper;

import com.weatherplatform.authservice.dto.UserResponse;
import com.weatherplatform.authservice.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User entity) {
        return UserResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .email(entity.getEmail())
                .role(entity.getRole())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
