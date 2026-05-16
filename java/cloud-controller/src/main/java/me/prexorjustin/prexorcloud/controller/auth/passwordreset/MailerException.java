package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

public final class MailerException extends Exception {

    public MailerException(String message) {
        super(message);
    }

    public MailerException(String message, Throwable cause) {
        super(message, cause);
    }
}
