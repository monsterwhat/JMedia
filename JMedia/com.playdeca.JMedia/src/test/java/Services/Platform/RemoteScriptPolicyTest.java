package Services.Platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Installer script policy. Offline: no network, no process, no credentials.
 */
class RemoteScriptPolicyTest {

    private static final String DENO = "https://deno.land/install.sh";
    private static final String BREW = "https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh";
    private static final String PINNED = "9f2c1d4b0a7e5f6382910a4b6c8d0e2f1a3b5c7d9e0f2a4b6c8d0e2f1a3b5c7d";

    @Test
    void unpinnedScriptIsRefusedByDefault() {
        assertFalse(RemoteScriptPolicy.mayExecute(DENO, null, false));
        assertFalse(RemoteScriptPolicy.mayExecute(BREW, null, false));
    }

    @Test
    void emptyOrMalformedDigestDoesNotCountAsPinned() {
        assertFalse(RemoteScriptPolicy.mayExecute(DENO, "", false));
        assertFalse(RemoteScriptPolicy.mayExecute(DENO, "abc123", false));
        assertFalse(RemoteScriptPolicy.mayExecute(DENO, PINNED + "00", false));
        assertFalse(RemoteScriptPolicy.mayExecute(DENO, "zz" + PINNED.substring(2), false));
    }

    @Test
    void aPinnedHttpsScriptMayRun() {
        assertTrue(RemoteScriptPolicy.isPinnedDigest(PINNED));
        assertTrue(RemoteScriptPolicy.mayExecute(DENO, PINNED, false));
    }

    @Test
    void plainHttpIsNeverAllowedEvenWithADigest() {
        assertFalse(RemoteScriptPolicy.mayExecute("http://deno.land/install.sh", PINNED, false));
        assertFalse(RemoteScriptPolicy.mayExecute("http://deno.land/install.sh", null, true));
        assertFalse(RemoteScriptPolicy.mayExecute(null, PINNED, true));
    }

    @Test
    void explicitOptInRestoresTheOldBehaviourForHttpsOnly() {
        assertTrue(RemoteScriptPolicy.mayExecute(DENO, null, true));
    }

    @Test
    void manualCommandShowsUrlAndPrivilege() {
        String command = RemoteScriptPolicy.manualCommand(DENO);

        assertTrue(command.contains(DENO));
        assertTrue(command.contains("curl -fsSL"));
        assertTrue(command.contains("sudo"));
    }

    @Test
    void refusalMessageNamesTheToolUrlAndTheOptInProperty() {
        String message = RemoteScriptPolicy.refusalMessage("Deno", DENO);

        assertTrue(message.contains("Deno"));
        assertTrue(message.contains(DENO));
        assertTrue(message.contains(RemoteScriptPolicy.ALLOW_UNVERIFIED_PROPERTY));
        assertTrue(message.contains("pinned to a checksum"));
    }
}
