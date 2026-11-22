package Services;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * First-run credential policy for the bootstrap administrator.
 * <p>
 * Deliberately free of CDI and persistence so the rules can be unit tested
 * without a database: password generation, the minimum length policy, and the
 * list of passwords that older builds shipped in source.
 */
public final class BootstrapCredentials {

    /** Minimum length for any password set through the change-password flow. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * Passwords that older builds hardcoded and created on every fresh install.
     * They are public knowledge, so they can never be a valid password again.
     */
    private static final List<String> LEGACY_DEFAULTS = List.of(
            "changeme1234",
            "custom password1234");

    /** 18 random bytes encode to 24 URL-safe characters. */
    private static final int GENERATED_BYTES = 18;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BootstrapCredentials() {
    }

    /**
     * Generate a one-time bootstrap password. 144 bits from {@link SecureRandom},
     * URL-safe so it survives copy/paste out of a log file or terminal.
     */
    public static String generate() {
        byte[] raw = new byte[GENERATED_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** True when the password is one that older builds shipped as a default. */
    public static boolean isLegacyDefault(String password) {
        if (password == null) {
            return false;
        }
        for (String legacy : LEGACY_DEFAULTS) {
            if (legacy.equals(password)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code password} is acceptable for a user-chosen password. */
    public static boolean meetsPolicy(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return false;
        }
        return !isLegacyDefault(password);
    }

    /** Human-readable policy, used verbatim in API error responses. */
    public static String policyMessage() {
        return "Password must be at least " + MIN_PASSWORD_LENGTH
                + " characters and must not be a previously shipped default.";
    }
}
