package Services.Platform;

/**
 * Policy for installers that download a script and hand it to a shell.
 * <p>
 * Piping a mutable remote document into {@code bash} or {@code sh} means the
 * content executed is whatever the server chooses to serve at that moment, with
 * the privileges of the JMedia process (root for {@code executeCommandAsRoot}).
 * That cannot be reviewed or reproduced, so it is not allowed by default.
 * <p>
 * Pure and static on purpose: the decision is unit tested without touching the
 * network, the filesystem, or a process.
 */
public final class RemoteScriptPolicy {

    /**
     * Opt-in escape hatch for users who want the previous behaviour:
     * {@code -Djmedia.install.allow-unverified-remote-scripts=true}.
     */
    public static final String ALLOW_UNVERIFIED_PROPERTY = "jmedia.install.allow-unverified-remote-scripts";

    private RemoteScriptPolicy() {
    }

    /**
     * Decide whether a downloaded script may be executed.
     *
     * @param url             where the script would come from
     * @param sha256          pinned digest of the script, or null when unknown
     * @param allowUnverified operator opted in to unpinned execution
     * @return true only for https with a pinned digest, or for the explicit opt-in
     */
    public static boolean mayExecute(String url, String sha256, boolean allowUnverified) {
        if (url == null || !url.startsWith("https://")) {
            return false;
        }
        if (allowUnverified) {
            return true;
        }
        return isPinnedDigest(sha256);
    }

    /** True when the value is a 64-character hexadecimal SHA-256 digest. */
    public static boolean isPinnedDigest(String sha256) {
        return sha256 != null && sha256.matches("[0-9a-fA-F]{64}");
    }

    /**
     * The command a human can read, verify and run themselves. This is what the
     * UI shows instead of executing the script silently.
     */
    public static String manualCommand(String url) {
        if (url == null || url.isBlank()) {
            return "# install the tool from its official documentation";
        }
        String shell = url.endsWith(".ps1") ? "powershell -File" : "bash";
        return "curl -fsSL " + url + " | sudo " + shell;
    }

    /** One-paragraph explanation shown in the install log. */
    public static String refusalMessage(String tool, String url) {
        return "Refusing to execute the " + tool + " installer script from " + url + " automatically: "
                + "the content is not pinned to a checksum, so what runs cannot be reviewed or repeated. "
                + "Run it yourself after reading it, or start JMedia with -D" + ALLOW_UNVERIFIED_PROPERTY
                + "=true to accept the risk.\n";
    }
}
