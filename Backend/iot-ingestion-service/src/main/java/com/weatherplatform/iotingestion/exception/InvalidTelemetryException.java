package com.weatherplatform.iotingestion.exception;

public class InvalidTelemetryException extends RuntimeException {
    public InvalidTelemetryException(String message) {
        super(message);
    }
}
