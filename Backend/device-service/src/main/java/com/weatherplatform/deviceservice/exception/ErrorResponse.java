package com.weatherplatform.deviceservice.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    boolean success;
    int status;
    String error;
    String message;
    String path;
    @Builder.Default
    LocalDateTime timestamp = LocalDateTime.now();
}
