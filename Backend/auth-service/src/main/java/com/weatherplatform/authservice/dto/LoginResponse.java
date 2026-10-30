package com.weatherplatform.authservice.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LoginResponse {
    String token;
    String type;
    Long expiresIn;
    UserResponse user;
}
