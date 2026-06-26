package me.prexorjustin.prexorcloud.controller.group.spec.secret;

/**
 * A {@code SECRET}-typed variable's reference could not be fetched from its backend. The message names
 * the reference scheme/key and the underlying cause — never the resolved value — so it is safe to log
 * and to surface to an operator.
 */
public class SecretResolutionException extends Exception {

    public SecretResolutionException(String message) {
        super(message);
    }

    public SecretResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
