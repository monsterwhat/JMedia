package Filters;

import Models.Music.Playlist;
import Models.Settings.Profile;
import Models.Settings.User;
import Services.AuthService;
import Services.PlaylistService;
import Services.ProfileService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops one account from reading or changing another account's private data
 * under {@code /api/music/playlists}.
 * <p>
 * The resource takes ids straight from the path and used to act on them without
 * checking who was asking, so any signed-in user could list, rename or delete a
 * family member's playlists by guessing ids. The guard lives in one place so
 * every current and future endpoint is covered.
 * <p>
 * Ownership rules mirror the service layer: a playlist is readable by anyone
 * when it is global (that is what "shared" means here) and otherwise only by the
 * account that owns its profile. Writes are always owner-or-admin, even for a
 * global playlist. The create endpoint carries its target profile in the request
 * body, which a filter must not consume; {@code MusicPlaylistApi.createPlaylist}
 * checks that one itself.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class PlaylistAccessFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(PlaylistAccessFilter.class);
    private static final String PREFIX = "/api/music/playlists";

    @Inject
    AuthService authService;

    @Inject
    ProfileService profileService;

    @Inject
    PlaylistService playlistService;

    @Override
    public void filter(ContainerRequestContext request) {
        UriInfo uri = request.getUriInfo();
        if (!uri.getPath().startsWith(PREFIX)) {
            return;
        }

        jakarta.ws.rs.core.Cookie sessionCookie = request.getCookies().get("JMEDIA_SESSION");
        User caller = sessionCookie == null ? null : authService.getCurrentUser(sessionCookie.getValue());
        if (caller == null) {
            // Unauthenticated calls are the auth filter's job; keep one 401 path.
            return;
        }
        if ("admin".equals(caller.getGroupName())) {
            return;
        }

        String method = request.getMethod();
        boolean read = "GET".equals(method);
        var params = uri.getPathParameters();

        // GET /{profileId} lists a profile's playlists; PUT|DELETE /{id} target a
        // playlist. The same URL shape means different things per method.
        Long profileId = read ? firstLong(params.getFirst("profileId"), params.getFirst("id")) : null;
        Long playlistId = read
                ? firstLong(params.getFirst("playlistId"), null)
                : firstLong(params.getFirst("playlistId"), params.getFirst("id"));

        if (playlistId != null) {
            Playlist playlist = playlistService.find(playlistId);
            if (playlist != null) {
                boolean owned = owns(caller, playlist.getProfileId());
                boolean sharedRead = read && Boolean.TRUE.equals(playlist.getIsGlobal());
                if (!owned && !sharedRead) {
                    deny(request, caller, "playlist " + playlistId + " belongs to another account");
                    return;
                }
            }
        }

        if (profileId != null && !owns(caller, profileId)) {
            deny(request, caller, "profile " + profileId + " belongs to another account");
        }
    }

    private void deny(ContainerRequestContext request, User caller, String reason) {
        LOG.warn("Blocked {} {} for user '{}': {}", request.getMethod(),
                request.getUriInfo().getPath(), caller.getUsername(), reason);
        request.abortWith(Response.status(Response.Status.FORBIDDEN)
                .entity("{\"success\":false,\"error\":\"Profile access denied\"}")
                .type("application/json")
                .build());
    }

    private boolean owns(User caller, Long profileId) {
        if (profileId == null) {
            return false;
        }
        Profile profile = profileService.findById(profileId);
        return profile != null && caller.id != null && caller.id.equals(profile.userId);
    }

    private static Long firstLong(String first, String second) {
        Long value = asLong(first);
        return value != null ? value : asLong(second);
    }

    private static Long asLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
