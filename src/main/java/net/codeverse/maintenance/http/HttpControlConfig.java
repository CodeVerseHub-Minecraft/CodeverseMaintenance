package net.codeverse.maintenance.http;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the control interface.
 *
 * Disabled by default. An interface that can close the network is worth opening
 * deliberately rather than by accident, and an unused open port is a liability
 * whatever is behind it.
 */
public final class HttpControlConfig {

    public boolean enabled = false;
    public String bindAddress = "127.0.0.1";
    public int port = 18791;

    /**
     * Addresses permitted to reach the interface, checked before any credential
     * is examined. The Discord bot runs on a host with a fixed address, so this
     * is a short list rather than a nuisance.
     */
    public List<String> allowedAddresses = new ArrayList<>(List.of("127.0.0.1"));

    /** Generated on first enable so the interface is never left credentialless. */
    public String token = "";

    public int signatureToleranceSeconds = 30;
    public int authFailuresBeforeLockout = 5;
    public int lockoutSeconds = 900;

    public void validate() {
        if (!enabled) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("http.token is blank while the interface is enabled");
        }
        if (allowedAddresses == null || allowedAddresses.isEmpty()) {
            throw new IllegalStateException("http.allowedAddresses cannot be empty while the interface "
                    + "is enabled. The allowlist is checked before the token, so an empty list would "
                    + "mean the token is the only thing between anyone and closing the network.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("http.port must be a valid port");
        }
        if (signatureToleranceSeconds < 5) {
            throw new IllegalStateException("http.signatureToleranceSeconds below 5 will refuse honest "
                    + "requests on any clock that drifts at all");
        }
        if (authFailuresBeforeLockout < 1) {
            throw new IllegalStateException("http.authFailuresBeforeLockout must be at least 1");
        }
    }
}
