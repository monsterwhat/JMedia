package Controllers;

import Models.Torrents.Core.Torrent;
import Models.Torrents.Identity.IdentityKey;
import Models.Torrents.Identity.PeerReference;
import Models.Torrents.Network.TorrentRating;
import Services.Torrents.IdentityService;
import Services.Torrents.TorrentService;
import Services.Torrents.PeerService;
import Services.Torrents.RatingService;
import Services.Torrents.TrustService;
import API.WS.WebSocketManager; 
import static bt.magnet.UtMetadata.request;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject; 
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.security.PublicKey;
import java.security.KeyFactory; 
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64; 

@ApplicationScoped
public class TorrentController {

    @Inject
    TorrentService torrentService;
    @Inject
    PeerService peerService;
    @Inject
    RatingService ratingService;
    @Inject
    TrustService trustService;
    @Inject
    IdentityService identityService;
    @Inject
    WebSocketManager webSocketManager;

    @Inject
    Services.Torrents.BanService banService;

    @Inject
    SettingsService settingsService;

    private final Map<UUID, Torrent> activeCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    // Fetch a torrent (cached if available)
    public Torrent getTorrent(UUID id) {
        if (!settingsService.getOrCreateSettings().getTorrentBrowsingEnabled()) {
            throw new IllegalStateException("Torrent browsing is disabled. Cannot perform getTorrent operation.");
        }
        return activeCache.computeIfAbsent(id, torrentService::findById);
    }

    public List<Torrent> listAllTorrents() {
        if (!settingsService.getOrCreateSettings().getTorrentBrowsingEnabled()) {
            throw new IllegalStateException("Torrent browsing is disabled. Cannot perform listAllTorrents operation.");
        }
        return torrentService.findAll();
    }

    public Models.DTOs.PaginatedTorrents listAllTorrentsPaginated(int pageNumber, int pageSize) {
        if (!settingsService.getOrCreateSettings().getTorrentBrowsingEnabled()) {
            throw new IllegalStateException("Torrent browsing is disabled. Cannot perform listAllTorrentsPaginated operation.");
        }
        List<Torrent> torrents = torrentService.findAll(pageNumber, pageSize, "createdAt", "DESC");
        long totalCount = torrentService.countAll();
        return new Models.DTOs.PaginatedTorrents(torrents, totalCount);
    }

    public Models.DTOs.PaginatedTorrents listAllTorrentsPaginatedAndFiltered(int pageNumber, int pageSize, String tag, String sortBy, String order) {
        if (!settingsService.getOrCreateSettings().getTorrentBrowsingEnabled()) {
            throw new IllegalStateException("Torrent browsing is disabled. Cannot perform listAllTorrentsPaginatedAndFiltered operation.");
        }
        List<Torrent> torrents;
        long totalCount;

        // Default sorting if not provided
        if (sortBy == null || sortBy.trim().isEmpty()) {
            sortBy = "createdAt";
        }
        if (order == null || order.trim().isEmpty()) {
            order = "DESC";
        }

        if (tag != null && !tag.trim().isEmpty()) {
            torrents = torrentService.findAllByTag(tag, pageNumber, pageSize, sortBy, order);
            totalCount = torrentService.countAllByTag(tag);
        } else {
            torrents = torrentService.findAll(pageNumber, pageSize, sortBy, order);
            totalCount = torrentService.countAll();
        }
        return new Models.DTOs.PaginatedTorrents(torrents, totalCount);
    }

    // Called when a peer connects via WebSocket
    public void onPeerConnected(PeerReference peer) {
        if (!settingsService.getOrCreateSettings().getTorrentBrowsingEnabled()) {
            throw new IllegalStateException("Torrent browsing is disabled. Cannot perform onPeerConnected operation.");
        }
        if (peer == null) {
            return;
        }
        peerService.updateLastSeen(peer);
        broadcastPeerEvent(peer, "peer_connected");
    }

    // Called when a peer registers or updates their information via WebSocket
    public boolean onPeerRegistered(String peerId, String publicKey, String ipAddress) {
        PeerReference peer = peerService.registerOrUpdatePeer(peerId, publicKey, ipAddress);
        System.out.println("[Controller] Registered/Updated peer: " + peerId);

        if (banService.isBanned(peer)) {
            System.out.println("[Controller] Banned peer connected: " + peerId);
            return true; // Indicate that the peer is banned
        }
        return false; // Indicate that the peer is not banned
    }

    // Called when a peer rates a torrent
    public void onPeerRatedTorrent(UUID torrentId, UUID peerId, boolean liked, byte[] signature) {
        Torrent torrent = getTorrent(torrentId);
        PeerReference peer = peerService.findById(peerId);
        if (torrent == null || peer == null) {
            return;
        }

        TorrentRating rating = new TorrentRating();
        rating.setTorrent(torrent);
        rating.setPeer(peer);
        rating.setLiked(liked);
        rating.setSignature(signature);
        rating.setCreatedAt(java.time.Instant.now()); // Set creation time for signature verification

        try {
            // Convert Base64 public key string to PublicKey object
            byte[] publicBytes = Base64.getDecoder().decode(peer.getPublicKey());
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA"); // Assuming RSA for now
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            if (!rating.verifyVote(publicKey)) {
                System.err.println("Invalid signature for torrent rating from peer: " + peer.getPeerId());
                return; // Reject the rating if signature is invalid
            }
        } catch (Exception e) {
            System.err.println("Error verifying torrent rating signature: " + e.getMessage());
            return; // Reject on error during verification
        }

        ratingService.addOrUpdateRating(torrent, peer, liked, signature);
        broadcastRatingUpdate(torrent, rating);
    }

    // Create a new torrent
    public Torrent createTorrent(Models.DTOs.CreateTorrentRequest request) {
        IdentityKey creator = identityService.findById(request.creatorId());
        if (creator == null) {
            throw new IllegalArgumentException("Creator not found");
        }

        // Verify torrent signature
        try {
            byte[] publicBytes = Base64.getDecoder().decode(creator.getPublicKey());
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            // The data that was signed by the client: infoHash + creatorId
            String signedData = request.infoHash() + creator.getId().toString();
            sig.update(signedData.getBytes());

            if (!sig.verify(request.signature())) {
                throw new IllegalArgumentException("Invalid signature for torrent creation");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Error verifying torrent signature: " + e.getMessage());
        }

        Torrent torrent = torrentService.createTorrent(request.name(), request.infoHash(), creator, request.tags());
        torrent.setTorrentSignature(request.signature()); // Store the verified signature
        activeCache.put(torrent.getId(), torrent);

        // Initialize trust only if a PeerReference exists for this identity
        peerService.findByPeerId(creator.getUserId())
                .ifPresent(peer -> {
                    trustService.initializeTrustForCreator(torrent, peer);
                    // Integrate trust-based logic for verification
                    double trustScore = trustService.getTrustScore(peer.getId());
                    if (trustScore > 0.7) { // Example threshold
                        torrent.setVerified(true);
                        torrentService.updateTorrent(torrent); // Persist the verified status
                    }
                });

        broadcastNewTorrent(torrent); // Re-using broadcastNewTorrent for now
        return torrent;
    }

    // Update an existing torrent
    public Torrent updateTorrent(UUID id, Models.DTOs.UpdateTorrentRequest request) {
        Torrent existingTorrent = torrentService.findById(id);
        if (existingTorrent == null) {
            throw new IllegalArgumentException("Torrent not found");
        }

        // Update fields
        existingTorrent.setName(request.name());
        existingTorrent.setInfoHash(request.infoHash());
        // Creator cannot be changed directly via update, it's part of initial creation
        existingTorrent.setTags(request.tags());
        existingTorrent.setVerified(request.verified());
        existingTorrent.setActive(request.active());

        Torrent updatedTorrent = torrentService.updateTorrent(existingTorrent);
        activeCache.put(updatedTorrent.getId(), updatedTorrent); // Update cache

        broadcastNewTorrent(updatedTorrent); // Re-using broadcastNewTorrent for now
        return updatedTorrent;
    }

    // Delete an existing torrent
    public void deleteTorrent(UUID id) {
        Torrent torrent = torrentService.findById(id);
        if (torrent == null) {
            throw new IllegalArgumentException("Torrent not found");
        }
        torrentService.deleteTorrent(id);
        activeCache.remove(id);
        broadcastTorrentDeletion(id); // New broadcast method
    }

    // -----------------------
    // Broadcast helpers
    // -----------------------
    private void broadcastNewTorrent(Torrent torrent) {
        if (torrent != null) {
            try {
                ObjectNode message = mapper.createObjectNode();
                message.put("type", "new_torrent");
                message.set("payload", mapper.valueToTree(torrent));
                webSocketManager.broadcastToTorrents(message.toString());
                System.out.println("[Socket] Broadcasting new torrent: " + torrent.getName());
            } catch (Exception e) {
                System.err.println("Error broadcasting new torrent: " + e.getMessage());
            }
        }
    }

    private void broadcastPeerEvent(PeerReference peer, String eventType) {
        if (peer != null) {
            try {
                ObjectNode message = mapper.createObjectNode();
                message.put("type", "peer_event");
                ObjectNode payload = mapper.createObjectNode();
                payload.put("peerId", peer.getPeerId());
                payload.put("eventType", eventType);
                message.set("payload", payload);
                webSocketManager.broadcastToTorrents(message.toString());
                System.out.println("[Socket] Peer event: " + eventType + " from " + peer.getPeerId());
            } catch (Exception e) {
                System.err.println("Error broadcasting peer event: " + e.getMessage());
            }
        }
    }

    private void broadcastRatingUpdate(Torrent torrent, TorrentRating rating) {
        if (torrent != null && rating != null) {
            try {
                ObjectNode message = mapper.createObjectNode();
                message.put("type", "rating_update");
                ObjectNode payload = mapper.createObjectNode();
                payload.put("torrentId", torrent.getId().toString());
                payload.put("liked", rating.isLiked());
                // Optionally, add more rating details if needed
                message.set("payload", payload);
                webSocketManager.broadcastToTorrents(message.toString());
                System.out.println("[Socket] Torrent rating updated: " + torrent.getName() + " liked=" + rating.isLiked());
            } catch (Exception e) {
                System.err.println("Error broadcasting rating update: " + e.getMessage());
            }
        }
    }

    private void broadcastTorrentDeletion(UUID torrentId) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("type", "torrent_deleted");
            ObjectNode payload = mapper.createObjectNode();
            payload.put("torrentId", torrentId.toString());
            message.set("payload", payload);
            webSocketManager.broadcastToTorrents(message.toString());
            System.out.println("[Socket] Broadcasting torrent deletion: " + torrentId);
        } catch (Exception e) {
            System.err.println("Error broadcasting torrent deletion: " + e.getMessage());
        }
    }
}
