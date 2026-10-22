package com.carequeue.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends email through Brevo's HTTPS API (port 443), not SMTP.
 * Use this when the host blocks outbound SMTP ports (Render does). Activate with CAREQUEUE_MAIL_MODE=brevo-api.
 * The API key comes from BREVO_API_KEY and is never logged.
 */
@Component
@ConditionalOnProperty(name = "carequeue.mail.mode", havingValue = "brevo-api")
public class BrevoApiEmailSender implements EmailSender {

    private static final URI ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${BREVO_API_KEY:}")
    private String apiKey;

    @Value("${carequeue.mail.from:no-reply@carequeue.invalid}")
    private String from;

    @Override
    public void send(String to, String subject, String body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Email is not configured (set BREVO_API_KEY)");
        }
        String json = "{"
            + "\"sender\":{\"name\":\"CareQueue\",\"email\":\"" + escape(from) + "\"},"
            + "\"to\":[{\"email\":\"" + escape(to) + "\"}],"
            + "\"subject\":\"" + escape(subject) + "\","
            + "\"textContent\":\"" + escape(body) + "\""
            + "}";

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
            .timeout(Duration.ofSeconds(15))
            .header("accept", "application/json")
            .header("content-type", "application/json")
            .header("api-key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                // Brevo's error text describes the problem (for example an unverified sender). It contains no secrets.
                throw new IllegalStateException("Brevo API rejected the email: HTTP " + response.statusCode()
                    + " " + response.body());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Brevo API unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Brevo API call interrupted", e);
        }
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
