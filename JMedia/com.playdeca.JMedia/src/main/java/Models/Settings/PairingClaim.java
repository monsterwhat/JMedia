package Models.Settings;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * QR pairing claims for TV clients (see API.Rest.PairingAPI).
 *
 * A TV that cannot type asks for a short code, renders it as a QR code, and
 * polls until a signed-in phone claims it. The claim carries the real server
 * URL plus a single-use token, which the TV redeems for a session cookie.
 * No password ever crosses the wire.
 *
 * Lifecycle: PENDING → CLAIMED → REDEEMED, or EXPIRED. Rows are tiny and
 * swept opportunistically (see Services.PairingService#sweepExpired).
 */
@Entity
@Table(name = "pairing_claim", indexes = {
        @Index(name = "idx_pairing_code", columnList = "code", unique = true)
})
public class PairingClaim extends PanacheEntity {

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String REDEEMED = "REDEEMED";
    public static final String EXPIRED = "EXPIRED";

    /** 6 characters from an unambiguous alphabet (no O/0, I/1, L). */
    @Column(nullable = false, unique = true, length = 16)
    public String code;

    @Column(nullable = false, length = 16)
    public String status = PENDING;

    public Instant createdAt;
    public Instant expiresAt;

    /** Server the TV should connect to after claiming (phone-supplied). */
    @Column(length = 512)
    public String targetUrl;

    public Long profileId;
    public String deviceName;
    public String userId;
    public String username;

    /**
     * Single-use device token. Stored in plaintext by design: it lives at most
     * ~2 minutes, is deleted on redeem, and anyone able to read this row
     * already has filesystem access (where the session table they could
     * equally abuse also lives). Never logged.
     */
    @Column(length = 128)
    public String token;

    public Instant tokenExpiresAt;

    /** Poll guard so a stuck TV cannot hammer one code forever. */
    public int pollCount = 0;

    public static PairingClaim findByCode(String code) {
        if (code == null) return null;
        return find("code", code).firstResult();
    }

    public static PairingClaim findLiveToken(String token) {
        if (token == null || token.isBlank()) return null;
        return find("token = ?1 AND status = ?2", token, CLAIMED).firstResult();
    }
}
