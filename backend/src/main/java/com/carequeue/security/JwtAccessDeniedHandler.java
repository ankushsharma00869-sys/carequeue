package com.carequeue.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.carequeue.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
/**
 * Writes 403 directly. Using response.sendError(403) would make Tomcat forward to /error, and
 * the JWT filter does not run on error dispatches, so the forwarded request is anonymous and the
 * entry point turns it into 401. Writing the response here avoids that second dispatch.
 */
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    // Spring Boot's auto-configured ObjectMapper (has the JSR-310 module registered for
    // LocalDateTime, same one the rest of the app's JSON responses already rely on).
    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpStatus.FORBIDDEN.value());

        ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.FORBIDDEN.value())
            .error("Forbidden")
            .message("You do not have permission to perform this action.")
            .path(request.getRequestURI())
            .build();

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
