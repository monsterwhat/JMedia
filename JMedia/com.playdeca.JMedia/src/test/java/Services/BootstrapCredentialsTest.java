package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * First-run credential rules. Offline, no CDI, no database.
 */
class BootstrapCredentialsTest {

    @Test
    void generatedPasswordIsLongEnoughAndUrlSafe() {
        String password = BootstrapCredentials.generate();

        assertTrue(password.length() >= 20, "generated password was only " + password.length() + " chars");
        assertTrue(password.matches("[A-Za-z0-9_-]+"), "unexpected characters: " + password);
    }

    @Test
    void generatedPasswordsAreNotRepeated() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertTrue(seen.add(BootstrapCredentials.generate()), "generator repeated a value");
        }
    }

    @Test
    void noShippedDefaultIsAcceptedAnyMore() {
        assertFalse(BootstrapCredentials.meetsPolicy("changeme1234"));
        assertFalse(BootstrapCredentials.meetsPolicy("custom password1234"));
        assertTrue(BootstrapCredentials.isLegacyDefault("changeme1234"));
        assertTrue(BootstrapCredentials.isLegacyDefault("custom password1234"));
    }

    @Test
    void generatedPasswordIsNeverALegacyDefault() {
        for (int i = 0; i < 200; i++) {
            assertFalse(BootstrapCredentials.isLegacyDefault(BootstrapCredentials.generate()));
        }
    }

    @Test
    void policyRequiresTheMinimumLength() {
        String tooShort = "a".repeat(BootstrapCredentials.MIN_PASSWORD_LENGTH - 1);
        String exactlyMinimum = "a".repeat(BootstrapCredentials.MIN_PASSWORD_LENGTH);

        assertFalse(BootstrapCredentials.meetsPolicy(tooShort));
        assertTrue(BootstrapCredentials.meetsPolicy(exactlyMinimum));
    }

    @Test
    void policyRejectsNull() {
        assertFalse(BootstrapCredentials.meetsPolicy(null));
        assertFalse(BootstrapCredentials.isLegacyDefault(null));
    }

    @Test
    void legacyCheckIsExactAndCaseSensitive() {
        assertFalse(BootstrapCredentials.isLegacyDefault("Changeme1234"));
        assertFalse(BootstrapCredentials.isLegacyDefault("changeme12345"));
        assertNotEquals("changeme1234", BootstrapCredentials.generate());
    }

    @Test
    void policyMessageStatesTheMinimum() {
        assertTrue(BootstrapCredentials.policyMessage()
                .contains(String.valueOf(BootstrapCredentials.MIN_PASSWORD_LENGTH)));
    }

    @Test
    void minimumLengthIsStrongEnoughToMatter() {
        assertEquals(12, BootstrapCredentials.MIN_PASSWORD_LENGTH);
    }
}
