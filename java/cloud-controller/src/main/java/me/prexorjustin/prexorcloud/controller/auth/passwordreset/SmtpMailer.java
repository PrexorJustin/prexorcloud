package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * SMTP mailer using jakarta.mail. Supports plain SMTP, STARTTLS, and implicit
 * TLS (smtps). Authentication is optional; when {@code username} is non-null,
 * SMTP AUTH is used.
 */
public final class SmtpMailer implements Mailer {

    private final Session session;
    private final String fromAddress;

    public SmtpMailer(Config config) {
        Objects.requireNonNull(config, "config");
        Properties props = new Properties();
        props.put("mail.smtp.host", config.host());
        props.put("mail.smtp.port", String.valueOf(config.port()));
        props.put(
                "mail.smtp.auth",
                String.valueOf(config.username() != null && !config.username().isBlank()));
        if (config.startTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        if (config.implicitTls()) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", String.valueOf(config.connectTimeoutMs()));
        props.put("mail.smtp.timeout", String.valueOf(config.readTimeoutMs()));
        props.put("mail.smtp.writetimeout", String.valueOf(config.readTimeoutMs()));

        Authenticator auth = null;
        if (config.username() != null && !config.username().isBlank()) {
            String username = config.username();
            String password = config.password() == null ? "" : config.password();
            auth = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            };
        }
        this.session = Session.getInstance(props, auth);
        this.fromAddress = config.fromAddress();
    }

    @Override
    public void send(String toAddress, String subject, String body) throws MailerException {
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress));
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(toAddress));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setText(body, StandardCharsets.UTF_8.name());
            Transport.send(message);
        } catch (Exception e) {
            throw new MailerException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    public record Config(
            String host,
            int port,
            boolean startTls,
            boolean implicitTls,
            String username,
            String password,
            String fromAddress,
            int connectTimeoutMs,
            int readTimeoutMs) {

        public Config {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(fromAddress, "fromAddress");
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("invalid SMTP port: " + port);
            }
            if (connectTimeoutMs <= 0) connectTimeoutMs = 10_000;
            if (readTimeoutMs <= 0) readTimeoutMs = 10_000;
        }
    }
}
