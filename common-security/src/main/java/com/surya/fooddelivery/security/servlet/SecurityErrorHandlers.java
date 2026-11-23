package com.surya.fooddelivery.security.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surya.fooddelivery.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Makes authentication and authorization failures speak the same JSON as every other error in the
 * project.
 *
 * <p>Spring Security rejects requests inside the filter chain, before any controller or
 * {@code @RestControllerAdvice} runs, so the shared {@code GlobalExceptionHandler} never sees
 * them. Left alone, a service would answer a valid request with a rich {@link ApiError} body and
 * an unauthenticated one with an empty 401 — clients would need two parsers. Wiring these two
 * handlers in keeps the contract uniform.
 *
 * <p>The messages stay deliberately vague ("authentication required" rather than "signature
 * invalid" or "token expired at ..."): a caller who is not authenticated has not earned a
 * debugging aid, and precise failure reasons help an attacker probe.
 */
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401 — the request carried no token, or one that failed verification. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED,
                "Authentication required. Send a valid bearer token in the Authorization header.");
    }

    /** 403 — the token is valid, but this principal lacks the role for this operation. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN,
                "Your account does not have permission to perform this action.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
