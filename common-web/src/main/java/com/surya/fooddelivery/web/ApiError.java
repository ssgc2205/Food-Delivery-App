package com.surya.fooddelivery.web;

import java.time.Instant;
import java.util.Map;

/**
 * The single error shape every service returns, so clients (and the gateway)
 * can handle failures uniformly instead of parsing per-service formats.
 *
 * @param fieldErrors field name -> message, populated only for validation failures
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, Map.of());
    }

    public static ApiError validation(int status, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status, "Bad Request", message, path, fieldErrors);
    }
}
