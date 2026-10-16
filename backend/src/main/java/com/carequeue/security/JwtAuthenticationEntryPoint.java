package com.carequeue.security;

import com.carequeue.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Handles requests to protected endpoints that carry no token, or an invalid/expired one.
 * Without this, Spring Security falls back to its default entry point, which is not
 * guaranteed to return a clean 401 JSON body — this makes "no/invalid token" explicitly
 * and consistently a 401, distinct from AccessDeniedException's 403 (valid token, wrong
 * role/ownership) in GlobalExceptionHandler.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // Spring Boot's auto-configured ObjectMapper (has the JSR-310 module registered for
    // LocalDateTime, same one the rest of the app's JSON responses already rely on).
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());

        ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.UNAUTHORIZED.value())
            .error("Unauthorized")
            .message("Authentication is required to access this resource.")
            .path(request.getRequestURI())
            .build();

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
