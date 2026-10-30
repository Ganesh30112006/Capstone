package com.weatherplatform.sensorservice.exception;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ErrorResponse {
    int status;
    String error;
    String message;
    LocalDateTime timestamp;
}
