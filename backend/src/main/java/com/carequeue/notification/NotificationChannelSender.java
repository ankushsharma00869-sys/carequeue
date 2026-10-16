package com.carequeue.notification;

/**
 * Delivery channel abstraction (Email, SMS, ...). Real providers (SendGrid, SES, Twilio,
 * MSG91) implement this interface and are selected by configuration; the dispatcher does
 * not change. The bundled implementations only LOG (with masked contact details) so local
 * development needs no paid provider - they do not deliver anything to a real person.
 */
public interface NotificationChannelSender {

    enum Channel { EMAIL, SMS }

    Channel channel();

    /** @return true if this sender can deliver to the message's recipient (e.g. has an address/number). */
    boolean supports(NotificationMessage message);

    void send(NotificationMessage message);
}
