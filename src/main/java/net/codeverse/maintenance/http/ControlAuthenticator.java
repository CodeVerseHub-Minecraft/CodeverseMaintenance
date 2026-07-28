package net.codeverse.maintenance.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decides whether a request may act on maintenance.
 *
 * Controls are applied cheapest first and, more importantly, in an order that
 * limits what an attacker learns. The address allowlist is checked before any
 * credential is examined, so a token lifted from a log is useless from anywhere
 * else, and every failure returns the same refusal so a caller cannot tell a
 * wrong address from a wrong signature from an endpoint that does not exist.
 *
 * The nonce is load bearing rather than decorative. Without it two identical
 * requests in the same second produce the same signature, and the second would
 * be refused as a replay, which would break a bot polling status. Signatures
 * are verified before the nonce is recorded, so a rejected request cannot burn
 * a nonce the legitimate caller intended to use.
 */
public final class ControlAuthenticator {

    private final HttpControlConfig config;
    private final Set<String> seenNonces = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> nonceExpiry = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockouts = new ConcurrentHashMap<>();

    public ControlAuthenticator(HttpControlConfig config) {
        this.config = config;
    }

    /** Every refusal reason, deliberately collapsed to one outcome for the caller. */
    public enum Result {
        ALLOWED,
        REFUSED
    }

    public Result authenticate(String address, String method, String path,
                               String timestamp, String nonce, String signature, byte[] body) {
        if (!config.allowedAddresses.contains(address)) {
            return Result.REFUSED;
        }
        Instant lockedUntil = lockouts.get(address);
        if (lockedUntil != null && Instant.now().isBefore(lockedUntil)) {
            return Result.REFUSED;
        }
        if (timestamp == null || nonce == null || signature == null) {
            return fail(address);
        }

        long skew;
        try {
            skew = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
        } catch (NumberFormatException malformed) {
            return fail(address);
        }
        if (skew > config.signatureToleranceSeconds) {
            return fail(address);
        }

        String material = String.join("\n", method.toUpperCase(), path, timestamp, nonce, sha256Hex(body));
        String expected = hmacHex(config.token, material);
        if (!constantTimeEquals(expected, signature)) {
            return fail(address);
        }

        // Recorded only after the signature verified, so a rejected request
        // cannot consume a nonce the real caller meant to use.
        purgeNonces();
        if (!seenNonces.add(nonce)) {
            return fail(address);
        }
        nonceExpiry.put(nonce, Instant.now().plusSeconds(config.signatureToleranceSeconds * 2L));
        failures.remove(address);
        return Result.ALLOWED;
    }

    private Result fail(String address) {
        int count = failures.computeIfAbsent(address, key -> new AtomicInteger()).incrementAndGet();
        if (count >= config.authFailuresBeforeLockout) {
            lockouts.put(address, Instant.now().plusSeconds(config.lockoutSeconds));
            failures.remove(address);
        }
        return Result.REFUSED;
    }

    private void purgeNonces() {
        Instant now = Instant.now();
        nonceExpiry.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(now)) {
                seenNonces.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String hmacHex(String key, String material) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /** Compared without leaking where two signatures first differ. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < a.length(); i++) {
            difference |= a.charAt(i) ^ b.charAt(i);
        }
        return difference == 0;
    }

    /** Generates a token for first start, so an enabled interface is never left credentialless. */
    public static String generateToken() {
        byte[] material = new byte[32];
        new java.security.SecureRandom().nextBytes(material);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    public Duration lockoutRemaining(String address) {
        Instant until = lockouts.get(address);
        return until == null ? Duration.ZERO : Duration.between(Instant.now(), until);
    }
}
