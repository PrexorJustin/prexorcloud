package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

/**
 * Outbound transactional mail. Implementations transport one message at a time;
 * they are not required to be transactional, but {@link #send} must either
 * deliver to the configured transport (or queue) or throw — silent drops would
 * mask account recovery failures.
 */
public interface Mailer {

    /**
     * Send a plain-text email. Implementations must use UTF-8.
     *
     * @throws MailerException when the transport refuses or fails.
     */
    void send(String toAddress, String subject, String body) throws MailerException;
}
