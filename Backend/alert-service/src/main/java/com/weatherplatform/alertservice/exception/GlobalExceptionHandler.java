package com.weatherplatform.alertservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        log.warn("Not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.builder()
                .success(false).status(404).error("Not Found")
                .message(ex.getMessage()).path(req.getRequestURI()).build());
    }

    /**
     * A body that will not bind — malformed JSON, a wrong field type, or a value
     * outside a closed enum such as an alert status — is the caller's mistake,
     * not a server fault. Without this it reaches the catch-all below and is
     * reported as 500, telling the client to retry something that can never
     * succeed. The parser's own message is not echoed: it repeats the caller's
     * input back and names internal classes.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("Unreadable request body for {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
                .success(false).status(400).error("Bad Request")
                .message("The request body could not be read. Check that the JSON is well formed and that every "
                        + "field holds a permitted value.")
                .path(req.getRequestURI()).build());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .distinct().reduce((a, b) -> a + "; " + b).orElse("Validation failed");
        log.warn("Validation failed: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
                .success(false).status(400).error("Bad Request")
                .message(message).path(req.getRequestURI()).build());
    }

    /**
     * A required query parameter that never arrived is the caller's mistake,
     * not a server fault. Without this handler it fell through to the
     * catch-all below and was reported as a 500, which told a retrying client
     * to try again against a request that can never succeed.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest req) {
        log.warn("Missing request parameter for {}: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
                .success(false).status(400).error("Bad Request")
                .message("Missing required parameter: " + ex.getParameterName())
                .path(req.getRequestURI()).build());
    }

    /** A path variable or query parameter of the wrong type, e.g. a non-numeric ID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        log.warn("Type mismatch for parameter '{}' on {}: {}", ex.getName(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.builder()
                .success(false).status(400).error("Bad Request")
                .message("Invalid value for parameter: " + ex.getName())
                .path(req.getRequestURI()).build());
    }

    /**
     * Method-level @PreAuthorize rejections are thrown inside the controller, so
     * they are resolved here by the advice rather than by Spring Security's
     * filter chain. Without this they would fall through to the catch-all below
     * and be reported to the client as 500 instead of 403.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied for {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.builder()
                .success(false).status(403).error("Forbidden")
                .message("You do not have permission to perform this action")
                .path(request.getRequestURI()).build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandled(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.builder()
                .success(false).status(500).error("Internal Server Error")
                .message("An unexpected error occurred").path(req.getRequestURI()).build());
    }
}
