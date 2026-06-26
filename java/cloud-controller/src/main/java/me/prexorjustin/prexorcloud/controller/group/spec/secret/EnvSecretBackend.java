package me.prexorjustin.prexorcloud.controller.group.spec.secret;

import java.util.function.Function;

/**
 * Resolves {@code env://NAME} to the controller process environment variable {@code NAME}. The lookup
 * is injectable so tests can supply a map without mutating the real environment; production uses
 * {@link System#getenv(String)}.
 */
public final class EnvSecretBackend implements SecretBackend {

    private final Function<String, String> env;

    public EnvSecretBackend() {
        this(System::getenv);
    }

    public EnvSecretBackend(Function<String, String> env) {
        this.env = env;
    }

    @Override
    public String scheme() {
        return "env";
    }

    @Override
    public String resolve(String reference) throws SecretResolutionException {
        String name = reference == null ? "" : reference.trim();
        if (name.isEmpty()) {
            throw new SecretResolutionException("env secret reference is missing a variable name");
        }
        String value = env.apply(name);
        if (value == null) {
            throw new SecretResolutionException("environment variable '" + name + "' is not set");
        }
        return value;
    }
}
