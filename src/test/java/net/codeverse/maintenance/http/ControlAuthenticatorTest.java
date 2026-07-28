package net.codeverse.maintenance.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The interface can close the whole network, so its refusals are worth pinning.
 */
class ControlAuthenticatorTest {

    private static final String TOKEN = "test-token-not-a-real-one";
    private HttpControlConfig config;
    private ControlAuthenticator auth;

    @BeforeEach
    void setUp() {
        config = new HttpControlConfig();
        config.enabled = true;
        config.token = TOKEN;
        config.allowedAddresses = List.of("127.0.0.1");
        config.authFailuresBeforeLockout = 3;
        auth = new ControlAuthenticator(config);
    }

    private ControlAuthenticator.Result attempt(String address, String nonce, long timestamp, String token) {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String material = String.join("\n", "GET", "/v1/status", String.valueOf(timestamp),
                nonce, ControlAuthenticator.sha256Hex(body));
        String signature = ControlAuthenticator.hmacHex(token, material);
        return auth.authenticate(address, "GET", "/v1/status",
                String.valueOf(timestamp), nonce, signature, body);
    }

    private ControlAuthenticator.Result valid() {
        return attempt("127.0.0.1", UUID.randomUUID().toString(), Instant.now().getEpochSecond(), TOKEN);
    }

    @Test
    void aCorrectlySignedRequestFromAnAllowedAddressPasses() {
        assertEquals(ControlAuthenticator.Result.ALLOWED, valid());
    }

    /**
     * The allowlist is checked before the credential, so a token lifted from a
     * log is useless from anywhere else.
     */
    @Test
    void anAddressOutsideTheAllowlistIsRefusedEvenWithACorrectSignature() {
        assertEquals(ControlAuthenticator.Result.REFUSED,
                attempt("10.0.0.5", UUID.randomUUID().toString(), Instant.now().getEpochSecond(), TOKEN));
    }

    @Test
    void aWrongTokenIsRefused() {
        assertEquals(ControlAuthenticator.Result.REFUSED,
                attempt("127.0.0.1", UUID.randomUUID().toString(),
                        Instant.now().getEpochSecond(), "wrong-token"));
    }

    @Test
    void aStaleTimestampIsRefused() {
        assertEquals(ControlAuthenticator.Result.REFUSED,
                attempt("127.0.0.1", UUID.randomUUID().toString(),
                        Instant.now().getEpochSecond() - 600, TOKEN));
    }

    /**
     * The nonce is what allows a bot to poll: two identical requests in the
     * same second would otherwise carry the same signature and the second would
     * be refused as a replay.
     */
    @Test
    void twoIdenticalRequestsInOneSecondBothPassWithDifferentNonces() {
        long now = Instant.now().getEpochSecond();
        assertEquals(ControlAuthenticator.Result.ALLOWED,
                attempt("127.0.0.1", UUID.randomUUID().toString(), now, TOKEN));
        assertEquals(ControlAuthenticator.Result.ALLOWED,
                attempt("127.0.0.1", UUID.randomUUID().toString(), now, TOKEN));
    }

    @Test
    void aReplayedNonceIsRefused() {
        String nonce = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        assertEquals(ControlAuthenticator.Result.ALLOWED, attempt("127.0.0.1", nonce, now, TOKEN));
        assertEquals(ControlAuthenticator.Result.REFUSED, attempt("127.0.0.1", nonce, now, TOKEN));
    }

    /**
     * A rejected request must not consume a nonce, or an attacker could burn
     * the ones a legitimate caller was about to use.
     */
    @Test
    void aRejectedRequestDoesNotBurnItsNonce() {
        String nonce = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        assertEquals(ControlAuthenticator.Result.REFUSED,
                attempt("127.0.0.1", nonce, now, "wrong-token"));
        assertEquals(ControlAuthenticator.Result.ALLOWED,
                attempt("127.0.0.1", nonce, now, TOKEN),
                "the honest caller can still use that nonce");
    }

    @Test
    void repeatedFailuresLockTheAddressOut() {
        long now = Instant.now().getEpochSecond();
        for (int i = 0; i < config.authFailuresBeforeLockout; i++) {
            attempt("127.0.0.1", UUID.randomUUID().toString(), now, "wrong-token");
        }
        assertEquals(ControlAuthenticator.Result.REFUSED, valid(),
                "a correct signature is refused while the address is locked out");
    }

    @Test
    void aGeneratedTokenIsLongAndNotReused() {
        String first = ControlAuthenticator.generateToken();
        String second = ControlAuthenticator.generateToken();
        assertNotEquals(first, second);
        org.junit.jupiter.api.Assertions.assertTrue(first.length() >= 32);
    }

    @Test
    void anEnabledInterfaceWithNoAllowlistRefusesToValidate() {
        HttpControlConfig empty = new HttpControlConfig();
        empty.enabled = true;
        empty.token = TOKEN;
        empty.allowedAddresses = List.of();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, empty::validate);
    }
}
