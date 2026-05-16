package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default mailer that logs each message instead of delivering it. Used in
 * development and as the fallback when SMTP is not configured. Captures the
 * last message in-process so tests can assert on the contents without parsing
 * log output.
 */
public final class LogMailer implements Mailer {

    private static final Logger logger = LoggerFactory.getLogger(LogMailer.class);

    private final AtomicReference<Sent> last = new AtomicReference<>();

    @Override
    public void send(String toAddress, String subject, String body) {
        last.set(new Sent(toAddress, subject, body));
        logger.info(
                "[mail] to={} subject={}\n{}\n[/mail]\n"
                        + "  (LogMailer is enabled — configure security.passwordReset.smtp to deliver email for real)",
                toAddress,
                subject,
                body);
    }

    public Sent lastSent() {
        return last.get();
    }

    public record Sent(String to, String subject, String body) {}
}
