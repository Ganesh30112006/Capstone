package com.weatherplatform.deviceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Standardized API response wrapper.
 * All controller responses are wrapped in this envelope for consistency.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    boolean success;
    String message;
    T data;
    @Builder.Default
    LocalDateTime timestamp = LocalDateTime.now();
    String path;
}
