package com.avi.idempotency.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Generates a deterministic SHA-256 fingerprint that uniquely identifies a
 * request context for idempotency purposes.
 *
 * <h2>Fingerprint Components</h2>
 * <ol>
 *   <li><b>Idempotency-Key</b> – the client-supplied key.</li>
 *   <li><b>HTTP Method</b> – prevents cross-method collisions (e.g. GET vs POST
 *       to the same URI with the same key).</li>
 *   <li><b>Request URI</b> – scopes the key to an endpoint.</li>
 *   <li><b>Principal</b> (optional) – when present, prevents one authenticated
 *       user from replaying another user's idempotency record.</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link MessageDigest} is not thread-safe so a new instance is obtained for
 * every call via {@link MessageDigest#getInstance(String)}, which uses a pool
 * internally in the JVM.</p>
 */
public final class FingerprintGenerator {

    private static final Logger log = LoggerFactory.getLogger(FingerprintGenerator.class);
    private static final String DELIMITER = "|";
    private static final String ALGORITHM = "SHA-256";

    private FingerprintGenerator() { /* utility class */ }

    /**
     * Generates a hex-encoded SHA-256 fingerprint.
     *
     * @param idempotencyKey the raw value of the {@code Idempotency-Key} header
     * @param httpMethod     HTTP method (e.g. {@code "POST"})
     * @param requestUri     the request URI path (e.g. {@code "/payments/charge"})
     * @param principal      the authenticated username, or {@code null} for anonymous requests
     * @return 64-character lowercase hex SHA-256 string
     */
    public static String generate(
            String idempotencyKey,
            String httpMethod,
            String requestUri,
            @Nullable String principal) {

        String raw = idempotencyKey
                + DELIMITER + httpMethod.toUpperCase()
                + DELIMITER + requestUri
                + DELIMITER + (principal != null ? principal : "anonymous");

        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java SE spec — should never happen
            log.error("SHA-256 algorithm not available", e);
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
