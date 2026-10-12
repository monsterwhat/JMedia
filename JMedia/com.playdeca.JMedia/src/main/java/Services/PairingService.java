package Services;

import Models.Settings.PairingClaim;
import Models.Settings.Profile;
import Models.Settings.Session;
import Models.Settings.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * QR pairing for TV clients (see API.Rest.PairingAPI and docs/pairing-qr.md in
 * the TV client repo).
 *
 * The TV polls; the server never calls back. All state lives in short-lived
 * {@link PairingClaim} rows that are swept opportunistically, so no scheduler
 * or cleanup job is required.
 */
@ApplicationScoped
public class PairingService {

    private static final Logger log = Logger.getLogger(PairingService.class);

    /** Claim codes live 5 minutes — enough to scan, short enough to brute-force. */
    private static final int CODE_TTL_MINUTES = 5;
    /** Tokens live 2 minutes and die on first use. */
    private static final int TOKEN_TTL_SECONDS = 120;
    /** A stuck TV polling every 2s trips this after ~13 minutes; expiry fires first. */
    private static final int MAX_POLLS_PER_CODE = 400;

    /** Unambiguous alphabet: no O/0, I/1, L (reads badly on TVs and phones). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    SessionService sessionService;

    @Inject
    RateLimitService rateLimitService;

    public static class PairingException extends RuntimeException {
        public final int status;

        public PairingException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ------------------------------------------------------------------ start

    /**
     * Issues a fresh claim code. Rate-limited per IP (10/hour) to prevent code
     * grinding.
     */
    @Transactional
    public Map<String, Object> start(String ip) {
        if (rateLimitService.isPairingLimited(ip)) {
            throw new PairingException(429, "Too many pairing attempts. Try again later.");
        }
        sweepExpired();

        String code = uniqueCode();
        Instant now = Instant.now();
        PairingClaim claim = new PairingClaim();
        claim.code = code;
        claim.status = PairingClaim.PENDING;
        claim.createdAt = now;
        claim.expiresAt = now.plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES);
        claim.persist();

        rateLimitService.recordPairingStart(ip);
        log.infof("Pairing started: code=%s from ip=%s", code, ip);

        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("expiresIn", CODE_TTL_MINUTES * 60);
        return result;
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (PairingClaim.findByCode(code) == null) return code;
        }
        throw new PairingException(503, "Could not issue a pairing code. Try again.");
    }

    // ------------------------------------------------------------------- land

    public enum LandState { PENDING, EXPIRED, CLAIMED }

    /**
     * Poll endpoint backing. Returns the live claim (PENDING or CLAIMED) and
     * bumps the poll guard; marks lapsed claims EXPIRED. Returns null when the
     * code never existed — the resource maps that to 404, same as pending, so
     * code existence is not oracle-able.
     */
    @Transactional
    public PairingClaim land(String code) {
        sweepExpired();
        PairingClaim claim = PairingClaim.findByCode(code);
        if (claim == null) return null;
        if (!PairingClaim.PENDING.equals(claim.status) && !PairingClaim.CLAIMED.equals(claim.status)) {
            return claim;
        }
        if (claim.expiresAt != null && Instant.now().isAfter(claim.expiresAt)) {
            claim.status = PairingClaim.EXPIRED;
            claim.persist();
            return claim;
        }
        claim.pollCount++;
        if (claim.pollCount > MAX_POLLS_PER_CODE) {
            claim.status = PairingClaim.EXPIRED;
            claim.persist();
            return claim;
        }
        claim.persist();
        return claim;
    }

    // ------------------------------------------------------------------ claim

    /**
     * Called by the signed-in phone. Binds the claim to the calling user, one
     * profile, and the server the TV should land on, then mints the single-use
     * token the TV will redeem.
     */
    @Transactional
    public String claim(String code, User user, String targetUrl, Long profileId, String deviceName) {
        PairingClaim claim = PairingClaim.findByCode(code);
        if (claim == null || PairingClaim.EXPIRED.equals(claim.status)
                || PairingClaim.REDEEMED.equals(claim.status)) {
            throw new PairingException(410, "Pairing code expired. Get a new one on the TV.");
        }
        if (!PairingClaim.PENDING.equals(claim.status)) {
            throw new PairingException(409, "Pairing code was already claimed.");
        }
        if (targetUrl == null || !(targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))
                || targetUrl.length() > 256) {
            throw new PairingException(400, "targetUrl must be an http(s) URL.");
        }
        Profile profile = profileId == null ? null : Profile.findById(profileId);
        if (profile == null) {
            throw new PairingException(400, "Unknown profile.");
        }
        if (profile.userId == null || user.id == null || !profile.userId.equals(user.id)) {
            throw new PairingException(403, "That profile does not belong to your account.");
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        claim.status = PairingClaim.CLAIMED;
        claim.targetUrl = targetUrl.replaceAll("/+$", "");
        claim.profileId = profile.id;
        claim.deviceName = deviceName != null && deviceName.length() <= 64 ? deviceName : "Apple TV";
        claim.userId = String.valueOf(user.id);
        claim.username = user.getUsername();
        claim.token = token;
        claim.tokenExpiresAt = Instant.now().plus(TOKEN_TTL_SECONDS, ChronoUnit.SECONDS);
        claim.persist();

        log.infof("Pairing claimed: code=%s by user=%s for profile=%d", code, user.getUsername(), profile.id);
        return token;
    }

    // ----------------------------------------------------------------- redeem

    public static class Redemption {
        public Session session;
        public String username;
        public Long profileId;
        public String profileName;
    }

    /**
     * Called by the TV against the *target* server. Validates the single-use
     * token, creates a real revocable session row carrying the device name,
     * and destroys the token. The resource turns the session into the same
     * JMEDIA_SESSION cookie login issues.
     */
    @Transactional
    public Redemption redeem(String token, String ip) {
        PairingClaim claim = PairingClaim.findLiveToken(token);
        if (claim == null) {
            throw new PairingException(401, "Invalid or expired pairing token.");
        }
        if (claim.tokenExpiresAt == null || Instant.now().isAfter(claim.tokenExpiresAt)) {
            claim.status = PairingClaim.EXPIRED;
            claim.token = null;
            claim.persist();
            throw new PairingException(401, "Pairing token expired. Claim the code again.");
        }

        Session session = sessionService.createSession(claim.userId, claim.username, ip);
        String profileName = null;
        if (claim.profileId != null) {
            Profile profile = Profile.findById(claim.profileId);
            if (profile != null) profileName = profile.name;
        }

        claim.status = PairingClaim.REDEEMED;
        claim.token = null;
        claim.persist();

        log.infof("Pairing redeemed: user=%s profile=%d device=%s",
                claim.username, claim.profileId, claim.deviceName);

        Redemption redemption = new Redemption();
        redemption.session = session;
        redemption.username = claim.username;
        redemption.profileId = claim.profileId;
        redemption.profileName = profileName;
        return redemption;
    }

    // ------------------------------------------------------------------ sweep

    /** Deletes lapsed claims. Called opportunistically; no scheduler needed. */
    @Transactional
    public int sweepExpired() {
        Instant now = Instant.now();
        int claims = (int) PairingClaim.delete(
                "(status = ?1 OR status = ?2) AND expiresAt < ?3",
                PairingClaim.PENDING, PairingClaim.CLAIMED, now);
        int dead = (int) PairingClaim.delete(
                "status = ?1 AND tokenExpiresAt < ?2", PairingClaim.CLAIMED, now);
        return claims + dead;
    }
}
