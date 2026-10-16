package com.carequeue.e2e;

import com.carequeue.websocket.SubscriptionDeniedEvent;
import org.awaitility.Awaitility;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observes the WebSocket layer from the server side, so tests can wait for real state instead of
 * sleeping. subscribed(destination) becomes true when the broker has registered a SUBSCRIBE;
 * deniedCount(...) rises when the interceptor refuses one.
 */
public class StompProbe {

    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final List<SubscriptionDeniedEvent> denied = new CopyOnWriteArrayList<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        String destination = SimpMessageHeaderAccessor.wrap(event.getMessage()).getDestination();
        if (destination != null) {
            subscribed.add(destination);
        }
    }

    @EventListener
    public void onDenied(SubscriptionDeniedEvent event) {
        denied.add(event);
    }

    public void awaitSubscribed(String destination) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
            .until(() -> subscribed.contains(destination));
    }

    public void awaitDenied(String destination) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
            .until(() -> deniedFor(destination) > 0);
    }

    public boolean isSubscribed(String destination) {
        return subscribed.contains(destination);
    }

    public int deniedFor(String destination) {
        return (int) denied.stream().filter(d -> destination.equals(d.destination())).count();
    }

    public int connectDenials() {
        return (int) denied.stream().filter(d -> "CONNECT".equals(d.command())).count();
    }

    @TestConfiguration
    public static class Config {
        @Bean
        public StompProbe stompProbe() {
            return new StompProbe();
        }
    }
}
