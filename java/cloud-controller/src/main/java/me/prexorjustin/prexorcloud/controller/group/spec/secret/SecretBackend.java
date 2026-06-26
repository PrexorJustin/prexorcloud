package me.prexorjustin.prexorcloud.controller.group.spec.secret;

/**
 * A pluggable source of secret values for {@code SECRET}-typed variables. A backend claims one URI
 * {@link #scheme() scheme} (e.g. {@code env}, {@code file}, {@code vault}); the {@link SecretResolver}
 * routes a {@code scheme://reference} to the matching backend at instance-start dispatch and never at
 * set time, so the plaintext value is fetched once, last-moment, into the transient start message and
 * never lands in a composition plan, a template snapshot, or an audit record.
 *
 * <p>Implementations are expected to be cheap and side-effect free per call and may be invoked
 * concurrently from placement threads. Two are built in ({@code env}, {@code file}); further backends
 * (Vault, cloud secret managers) register through the same SPI by being handed to a
 * {@link SecretResolver}.
 */
public interface SecretBackend {

    /** The URI scheme this backend resolves, lower-case and without the {@code ://}, e.g. {@code env}. */
    String scheme();

    /**
     * Resolve the scheme-stripped reference to its plaintext secret value.
     *
     * @param reference
     *            everything after {@code scheme://} — e.g. for {@code env://RCON_PASSWORD} this is
     *            {@code RCON_PASSWORD}
     * @return the secret's plaintext value (never null)
     * @throws SecretResolutionException
     *             if the reference is malformed or the secret is missing/unreadable; the message must
     *             not include the value
     */
    String resolve(String reference) throws SecretResolutionException;
}
