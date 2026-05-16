package me.prexorjustin.prexorcloud.security.signing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import me.prexorjustin.prexorcloud.common.io.ObjectMappers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verification hook for uploaded platform-module packages.
 *
 * <p>{@link #NOOP} accepts every prepared module without inspection. It is intended only
 * for tests and for the {@code runtime.profile=development} hot-path. Production startup
 * MUST reject {@link #NOOP} via configuration validation; pick {@link #failClosed()} or
 * {@link TrustRootVerifier#fromPemBundle(Path)} instead.
 *
 * <p>{@link #failClosed()} rejects every install with a clear error so misconfigured
 * production controllers fail fast rather than silently accepting unsigned packages.
 */
@FunctionalInterface
public interface PlatformModuleSignatureVerifier {

    /**
     * Subset of a prepared module exposed to verification. Decouples the verifier from
     * the controller's {@code PlatformModuleStore.PreparedModule} so the daemon-side
     * module store can reuse the same verifier with its own prepared-module shape.
     */
    record VerificationInput(Path sourceJar, String moduleId, String moduleVersion, String sha256) {
        public VerificationInput {
            Objects.requireNonNull(sourceJar, "sourceJar");
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(moduleVersion, "moduleVersion");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    PlatformModuleSignatureVerifier NOOP = input -> {};

    void verify(VerificationInput input);

    /**
     * Returns a verifier that always rejects installation. Used as the production default
     * when {@code modules.signing.required=true} but no trust root is configured: the
     * controller refuses to install rather than installing without verification.
     */
    static PlatformModuleSignatureVerifier failClosed() {
        return input -> {
            throw new SignatureVerificationException("module signing is required but no trust root is configured "
                    + "(set modules.signing.trustRoot or runtime.profile=development to skip)");
        };
    }

    /**
     * Verifier that loads a PEM trust bundle of public keys and accepts modules whose
     * sidecar {@code .sig} file (Base64-encoded SHA-256-with-RSA or SHA-256-with-EC over
     * the jar bytes) matches at least one trusted key.
     */
    final class TrustRootVerifier implements PlatformModuleSignatureVerifier {

        private static final Logger LOGGER = LoggerFactory.getLogger(TrustRootVerifier.class);

        private static final Pattern PEM_BLOCK = Pattern.compile(
                "-----BEGIN PUBLIC KEY-----([A-Za-z0-9+/=\\s]+?)-----END PUBLIC KEY-----", Pattern.DOTALL);

        private final List<PublicKey> trustedKeys;

        public TrustRootVerifier(List<PublicKey> trustedKeys) {
            this.trustedKeys = List.copyOf(Objects.requireNonNull(trustedKeys, "trustedKeys"));
            if (this.trustedKeys.isEmpty()) {
                throw new IllegalArgumentException("trustedKeys must not be empty");
            }
        }

        public static TrustRootVerifier fromPemBundle(Path bundle) {
            Objects.requireNonNull(bundle, "bundle");
            if (!Files.isRegularFile(bundle)) {
                throw new IllegalArgumentException("trust bundle does not exist: " + bundle);
            }
            try {
                String pem = Files.readString(bundle);
                List<PublicKey> keys = new ArrayList<>();
                var matcher = PEM_BLOCK.matcher(pem);
                while (matcher.find()) {
                    String body = matcher.group(1).replaceAll("\\s+", "");
                    byte[] decoded = Base64.getDecoder().decode(body);
                    keys.add(SignatureUtils.parsePublicKey(decoded));
                }
                if (keys.isEmpty()) {
                    throw new IllegalStateException("trust bundle has no PUBLIC KEY blocks: " + bundle);
                }
                return new TrustRootVerifier(keys);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read trust bundle: " + bundle, e);
            }
        }

        @Override
        public void verify(VerificationInput input) {
            Objects.requireNonNull(input, "input");
            Path jar = input.sourceJar();
            Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
            if (!Files.isRegularFile(sig)) {
                throw new SignatureVerificationException("missing signature sidecar: " + sig);
            }
            byte[] signatureBytes;
            byte[] payload;
            try {
                signatureBytes =
                        Base64.getDecoder().decode(Files.readString(sig).trim());
                payload = Files.readAllBytes(jar);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read module bytes for verification", e);
            }

            for (PublicKey key : trustedKeys) {
                if (SignatureUtils.verify(key, payload, signatureBytes)) {
                    LOGGER.info(
                            "module signature accepted: moduleId={} version={} sha256={} keyAlgo={}",
                            input.moduleId(),
                            input.moduleVersion(),
                            input.sha256(),
                            key.getAlgorithm());
                    return;
                }
            }
            throw new SignatureVerificationException(
                    "module signature did not match any trusted key (sha256=" + input.sha256() + ")");
        }
    }

    /**
     * Verifier that consumes a {@code <jar>.cosign.bundle} JSON file (the format produced by
     * {@code cosign sign-blob --bundle <out>.bundle <jar>}). The bundle carries a Base64
     * signature and either an embedded X.509 certificate (cosign-keyed-with-cert) or no cert
     * (cosign-keyed). The verifier matches the signature against keys derived from the trust
     * root:
     * <ul>
     *   <li>If the bundle has a cert and the trust root holds {@code CERTIFICATE} blocks, the
     *       cert chain is validated via PKIX against the trust anchors and the cert's public
     *       key is used to verify the signature.</li>
     *   <li>If the bundle has a cert and the trust root holds raw {@code PUBLIC KEY} blocks,
     *       the cert's public key must match one of the trusted keys exactly.</li>
     *   <li>If the bundle has no cert, the signature is verified against each trusted public
     *       key directly.</li>
     * </ul>
     *
     * <p>Rekor transparency-log verification is enforced offline when constructed with a
     * non-{@link RekorPolicy#DISABLED} policy: the {@code rekorBundle.SignedEntryTimestamp}
     * is verified against trusted Rekor public keys over the canonical JSON of
     * {@code rekorBundle.Payload} (sorted-key, no-whitespace JSON of {@code body},
     * {@code integratedTime}, {@code logID}, {@code logIndex}). No network access is
     * required — the trust root carries Rekor's public key locally. {@code REQUIRE_SET}
     * additionally rejects bundles that do not carry a rekorBundle. Inclusion-proof
     * Merkle-path verification is intentionally out of scope; cosign's SET is sufficient
     * to bind the signature to a Rekor log entry.
     */
    final class CosignBundleVerifier implements PlatformModuleSignatureVerifier {

        public enum RekorPolicy {
            /** No transparency-log enforcement. {@code rekorBundle} is parsed but ignored. */
            DISABLED,
            /** {@code rekorBundle.SignedEntryTimestamp} must be present and verify against the trust root. */
            REQUIRE_SET
        }

        private static final Logger LOGGER = LoggerFactory.getLogger(CosignBundleVerifier.class);
        private static final ObjectMapper JSON = ObjectMappers.standard();

        private static final Pattern PUBLIC_KEY_BLOCK = Pattern.compile(
                "-----BEGIN PUBLIC KEY-----([A-Za-z0-9+/=\\s]+?)-----END PUBLIC KEY-----", Pattern.DOTALL);
        private static final Pattern CERT_BLOCK = Pattern.compile(
                "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----", Pattern.DOTALL);

        private final List<PublicKey> trustedKeys;
        private final List<X509Certificate> trustedCerts;
        private final RekorPolicy rekorPolicy;
        private final List<PublicKey> rekorTrustedKeys;

        public CosignBundleVerifier(List<PublicKey> trustedKeys, List<X509Certificate> trustedCerts) {
            this(trustedKeys, trustedCerts, RekorPolicy.DISABLED, List.of());
        }

        public CosignBundleVerifier(
                List<PublicKey> trustedKeys,
                List<X509Certificate> trustedCerts,
                RekorPolicy rekorPolicy,
                List<PublicKey> rekorTrustedKeys) {
            this.trustedKeys = List.copyOf(Objects.requireNonNull(trustedKeys, "trustedKeys"));
            this.trustedCerts = List.copyOf(Objects.requireNonNull(trustedCerts, "trustedCerts"));
            this.rekorPolicy = Objects.requireNonNull(rekorPolicy, "rekorPolicy");
            this.rekorTrustedKeys = List.copyOf(Objects.requireNonNull(rekorTrustedKeys, "rekorTrustedKeys"));
            if (this.trustedKeys.isEmpty() && this.trustedCerts.isEmpty()) {
                throw new IllegalArgumentException(
                        "trust root must contain at least one PUBLIC KEY or CERTIFICATE block");
            }
            if (this.rekorPolicy != RekorPolicy.DISABLED && this.rekorTrustedKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "rekorPolicy=" + this.rekorPolicy + " requires at least one Rekor public key");
            }
        }

        public static CosignBundleVerifier fromPemBundle(Path bundle) {
            Objects.requireNonNull(bundle, "bundle");
            if (!Files.isRegularFile(bundle)) {
                throw new IllegalArgumentException("trust bundle does not exist: " + bundle);
            }
            try {
                String pem = Files.readString(bundle);
                List<PublicKey> keys = new ArrayList<>();
                var keyMatcher = PUBLIC_KEY_BLOCK.matcher(pem);
                while (keyMatcher.find()) {
                    String body = keyMatcher.group(1).replaceAll("\\s+", "");
                    keys.add(SignatureUtils.parsePublicKey(Base64.getDecoder().decode(body)));
                }
                List<X509Certificate> certs = new ArrayList<>();
                var certMatcher = CERT_BLOCK.matcher(pem);
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                while (certMatcher.find()) {
                    String body = certMatcher.group(1).replaceAll("\\s+", "");
                    byte[] decoded = Base64.getDecoder().decode(body);
                    certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(decoded)));
                }
                if (keys.isEmpty() && certs.isEmpty()) {
                    throw new IllegalStateException("trust bundle has no PUBLIC KEY or CERTIFICATE blocks: " + bundle);
                }
                return new CosignBundleVerifier(keys, certs);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read trust bundle: " + bundle, e);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("failed to parse trust bundle: " + bundle, e);
            }
        }

        /**
         * Loads a Rekor public-key bundle (PEM with one or more {@code PUBLIC KEY} blocks). The
         * cosign-public-good Rekor pubkey is a single ECDSA P-256 key; operators running their
         * own Rekor instance can ship multiple keys here for rotation overlap.
         */
        public static List<PublicKey> loadRekorPublicKeys(Path pem) {
            Objects.requireNonNull(pem, "pem");
            if (!Files.isRegularFile(pem)) {
                throw new IllegalArgumentException("rekor public key file does not exist: " + pem);
            }
            try {
                String body = Files.readString(pem);
                List<PublicKey> keys = new ArrayList<>();
                var matcher = PUBLIC_KEY_BLOCK.matcher(body);
                while (matcher.find()) {
                    String b64 = matcher.group(1).replaceAll("\\s+", "");
                    keys.add(SignatureUtils.parsePublicKey(Base64.getDecoder().decode(b64)));
                }
                if (keys.isEmpty()) {
                    throw new IllegalStateException("rekor public key file has no PUBLIC KEY blocks: " + pem);
                }
                return keys;
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read rekor public key file: " + pem, e);
            }
        }

        /** Returns this verifier wrapped to enforce the supplied Rekor policy. */
        public CosignBundleVerifier withRekor(RekorPolicy policy, List<PublicKey> rekorKeys) {
            return new CosignBundleVerifier(trustedKeys, trustedCerts, policy, rekorKeys);
        }

        @Override
        public void verify(VerificationInput input) {
            Objects.requireNonNull(input, "input");
            Path jar = input.sourceJar();
            Path bundlePath = jar.resolveSibling(jar.getFileName() + ".cosign.bundle");
            if (!Files.isRegularFile(bundlePath)) {
                throw new SignatureVerificationException("missing cosign bundle: " + bundlePath);
            }

            JsonNode bundle;
            byte[] payload;
            byte[] signatureBytes;
            X509Certificate embeddedCert;
            try {
                bundle = JSON.readTree(Files.readAllBytes(bundlePath));
                payload = Files.readAllBytes(jar);
                String b64Signature = textField(bundle, "base64Signature");
                if (b64Signature == null || b64Signature.isBlank()) {
                    throw new SignatureVerificationException("cosign bundle missing base64Signature: " + bundlePath);
                }
                signatureBytes = Base64.getDecoder().decode(b64Signature);
                embeddedCert = parseEmbeddedCert(bundle);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read cosign bundle: " + bundlePath, e);
            } catch (GeneralSecurityException e) {
                throw new SignatureVerificationException(
                        "cosign bundle has unparseable cert: " + bundlePath + ": " + e.getMessage());
            }

            verifyRekorPolicy(bundle, bundlePath, input);

            PublicKey verifyKey;
            String trustSource;
            if (embeddedCert != null) {
                if (!trustedCerts.isEmpty()) {
                    validateCertChain(embeddedCert);
                    trustSource = "cert-chain";
                } else if (!trustedKeys.isEmpty()) {
                    PublicKey embeddedPub = embeddedCert.getPublicKey();
                    if (trustedKeys.stream().noneMatch(k -> sameKey(k, embeddedPub))) {
                        throw new SignatureVerificationException(
                                "cosign bundle cert public key not in trust root (sha256=" + input.sha256() + ")");
                    }
                    trustSource = "embedded-cert-pinned-key";
                } else {
                    throw new SignatureVerificationException("trust root is empty");
                }
                verifyKey = embeddedCert.getPublicKey();
                if (SignatureUtils.verify(verifyKey, payload, signatureBytes)) {
                    LOGGER.info(
                            "cosign signature accepted: moduleId={} version={} sha256={} keyAlgo={} via={}",
                            input.moduleId(),
                            input.moduleVersion(),
                            input.sha256(),
                            verifyKey.getAlgorithm(),
                            trustSource);
                    return;
                }
                throw new SignatureVerificationException(
                        "cosign signature did not verify with embedded cert (sha256=" + input.sha256() + ")");
            }

            // No cert in bundle — try every trusted public key directly.
            for (PublicKey key : trustedKeys) {
                if (SignatureUtils.verify(key, payload, signatureBytes)) {
                    LOGGER.info(
                            "cosign signature accepted: moduleId={} version={} sha256={} keyAlgo={} via=raw-key",
                            input.moduleId(),
                            input.moduleVersion(),
                            input.sha256(),
                            key.getAlgorithm());
                    return;
                }
            }
            throw new SignatureVerificationException(
                    "cosign signature did not match any trusted key (sha256=" + input.sha256() + ")");
        }

        private void verifyRekorPolicy(JsonNode bundle, Path bundlePath, VerificationInput input) {
            if (rekorPolicy == RekorPolicy.DISABLED) {
                return;
            }
            JsonNode rekorBundle = bundle.get("rekorBundle");
            if (rekorBundle == null || rekorBundle.isNull()) {
                throw new SignatureVerificationException(
                        "rekor policy " + rekorPolicy + " requires rekorBundle in cosign bundle: " + bundlePath);
            }
            String setB64 = textField(rekorBundle, "SignedEntryTimestamp");
            JsonNode payloadNode = rekorBundle.get("Payload");
            if (setB64 == null || setB64.isBlank() || payloadNode == null || !payloadNode.isObject()) {
                throw new SignatureVerificationException(
                        "rekorBundle missing SignedEntryTimestamp or Payload: " + bundlePath);
            }

            byte[] canonical = canonicalizeRekorPayload(payloadNode, bundlePath);
            byte[] setBytes;
            try {
                setBytes = Base64.getDecoder().decode(setB64);
            } catch (IllegalArgumentException _) {
                throw new SignatureVerificationException(
                        "rekorBundle SignedEntryTimestamp is not valid Base64: " + bundlePath);
            }

            for (PublicKey key : rekorTrustedKeys) {
                if (SignatureUtils.verify(key, canonical, setBytes)) {
                    LOGGER.info(
                            "rekor SET accepted: moduleId={} version={} sha256={} logIndex={} integratedTime={}",
                            input.moduleId(),
                            input.moduleVersion(),
                            input.sha256(),
                            payloadNode.path("logIndex").asLong(-1),
                            payloadNode.path("integratedTime").asLong(-1));
                    return;
                }
            }
            throw new SignatureVerificationException(
                    "rekor SET did not verify against any trusted Rekor public key (sha256=" + input.sha256() + ")");
        }

        /**
         * Builds the canonical JSON bytes the Rekor SET signs over: the {@code Payload}
         * sub-object with keys sorted alphabetically and no whitespace. cosign's reference
         * implementation uses the standard Go {@code encoding/json} key-sorted output for
         * structs, so the canonical key order is {@code body, integratedTime, logID, logIndex}
         * (uppercase {@code I} sorts before lowercase {@code n} in {@code logID} vs.
         * {@code logIndex}).
         */
        private static byte[] canonicalizeRekorPayload(JsonNode payload, Path bundlePath) {
            String body = textField(payload, "body");
            JsonNode integratedNode = payload.get("integratedTime");
            String logId = textField(payload, "logID");
            JsonNode logIndexNode = payload.get("logIndex");
            if (body == null
                    || logId == null
                    || integratedNode == null
                    || !integratedNode.canConvertToLong()
                    || logIndexNode == null
                    || !logIndexNode.canConvertToLong()) {
                throw new SignatureVerificationException(
                        "rekorBundle.Payload missing required fields (body, integratedTime, logID, logIndex): "
                                + bundlePath);
            }
            // Body and logID are pure ASCII (Base64 / hex); no JSON escaping required.
            StringBuilder sb = new StringBuilder(64 + body.length() + logId.length());
            sb.append("{\"body\":\"")
                    .append(body)
                    .append("\",\"integratedTime\":")
                    .append(integratedNode.asLong())
                    .append(",\"logID\":\"")
                    .append(logId)
                    .append("\",\"logIndex\":")
                    .append(logIndexNode.asLong())
                    .append('}');
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        private void validateCertChain(X509Certificate leaf) {
            try {
                Set<TrustAnchor> anchors = new HashSet<>();
                for (X509Certificate ca : trustedCerts) {
                    anchors.add(new TrustAnchor(ca, null));
                }
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                CertPath path = cf.generateCertPath(List.of(leaf));
                PKIXParameters params = new PKIXParameters(anchors);
                params.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX").validate(path, params);
            } catch (GeneralSecurityException e) {
                throw new SignatureVerificationException("cosign bundle cert chain invalid: " + e.getMessage());
            }
        }

        private static X509Certificate parseEmbeddedCert(JsonNode bundle) throws GeneralSecurityException {
            String cert = textField(bundle, "cert");
            if (cert == null || cert.isBlank()) {
                return null;
            }
            // cosign emits the cert as either a PEM string or base64-encoded PEM bytes.
            String pem;
            if (cert.contains("BEGIN CERTIFICATE")) {
                pem = cert;
            } else {
                pem = new String(Base64.getDecoder().decode(cert.replaceAll("\\s+", "")), StandardCharsets.UTF_8);
            }
            var matcher = CERT_BLOCK.matcher(pem);
            if (!matcher.find()) {
                throw new GeneralSecurityException("cert field has no CERTIFICATE block");
            }
            byte[] der = Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s+", ""));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        }

        private static String textField(JsonNode node, String name) {
            JsonNode child = node.get(name);
            return child == null || child.isNull() ? null : child.asText();
        }

        private static boolean sameKey(PublicKey a, PublicKey b) {
            return a.getAlgorithm().equalsIgnoreCase(b.getAlgorithm())
                    && java.util.Arrays.equals(a.getEncoded(), b.getEncoded());
        }
    }

    /** Thrown when signature verification rejects a prepared module. */
    final class SignatureVerificationException extends RuntimeException {
        public SignatureVerificationException(String message) {
            super(message);
        }
    }
}
