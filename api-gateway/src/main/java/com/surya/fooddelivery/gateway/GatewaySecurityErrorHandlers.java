package com.surya.fooddelivery.gateway;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The reactive counterpart of common-security's {@code SecurityErrorHandlers}, so a request
 * rejected at the gateway looks the same to a client as one rejected by a service.
 *
 * <p>The JSON is assembled by hand here rather than reusing the shared {@code ApiError} record.
 * That record lives in common-web, and common-web pulls in {@code spring-boot-starter-web} — put
 * that on this module's classpath and Spring Boot decides the application is a servlet app,
 * which stops Spring Cloud Gateway working at all. Twenty lines of duplication is the cheaper
 * side of that trade.
 */
@Component
public class GatewaySecurityErrorHandlers implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return write(exchange, HttpStatus.UNAUTHORIZED,
                "Authentication required. Obtain a token from POST /api/auth/login.");
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return write(exchange, HttpStatus.FORBIDDEN,
                "Your account does not have permission to access this resource.");
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"timestamp":"%s","status":%d,"error":"%s","message":"%s","path":"%s","fieldErrors":{}}"""
                .formatted(Instant.now(), status.value(), status.getReasonPhrase(), message,
                        exchange.getRequest().getPath().value());

        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
