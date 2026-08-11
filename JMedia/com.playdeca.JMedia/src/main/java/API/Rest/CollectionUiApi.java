package API.Rest;

import Models.Settings.Profile;
import Services.AuthService;
import Services.CollectionService;
import Services.SettingsService;
import Services.VideoService;
import io.quarkus.qute.Template;
import io.quarkus.qute.Location;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

@Path("/api/video/ui")
@Produces(MediaType.TEXT_HTML)
public class CollectionUiApi {

    @Inject
    CollectionService collectionService;

    @Inject
    VideoService videoService;

    @Inject
    SettingsService settingsService;

    @Inject @Location("collectionListContent.html")
    Template collectionListContent;

    @Inject @Location("collectionEntriesContent.html")
    Template collectionEntriesContent;

    @Inject @Location("collectionItemsFragment.html")
    Template collectionItemsFragment;

    @Inject
    AuthService authService;

    @GET
    @Path("/collections-fragment")
    @Blocking
    public String getCollectionsFragment(
            @Context HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit) {
        boolean isAdmin = authService.isAdmin(headers);
        Profile activeProfile = settingsService.getActiveProfile();
        long totalItems = collectionService.countCollections(activeProfile, isAdmin);
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;
        List<Models.Video.MediaCollection> collections = collectionService.findPaginatedCollections(page, limit, activeProfile, isAdmin);

        return collectionListContent
                .data("collections", collections)
                .data("totalItems", totalItems)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .render();
    }

    @GET
    @Path("/collections-fragment-more")
    @Blocking
    public String getCollectionsFragmentMore(
            @Context HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("limit") @DefaultValue("40") int limit) {
        boolean isAdmin = authService.isAdmin(headers);
        Profile activeProfile = settingsService.getActiveProfile();
        long totalItems = collectionService.countCollections(activeProfile, isAdmin);
        int totalPages = (int) Math.ceil((double) totalItems / limit);
        boolean hasMore = page < totalPages;
        int nextPage = page + 1;
        List<Models.Video.MediaCollection> collections = collectionService.findPaginatedCollections(page, limit, activeProfile, isAdmin);

        return collectionItemsFragment
                .data("collections", collections)
                .data("totalItems", totalItems)
                .data("limit", limit)
                .data("nextPage", nextPage)
                .data("hasMore", hasMore)
                .render();
    }

    @GET
    @Path("/collections/{collectionId}/entries-fragment")
    @Blocking
    public String getCollectionEntriesFragment(@Context HttpHeaders headers,
                                                @PathParam("collectionId") Long collectionId) {
        var collection = collectionService.getCollection(collectionId);
        if (collection == null) {
            return "<div class='notification is-danger'>Collection not found</div>";
        }
        var fragment = collectionService.getEntriesFragment(collectionId);
        if (fragment == null) {
            return "<div class='notification is-danger'>Collection not found</div>";
        }
        var organized = collectionService.organizeActiveVideos(fragment.videoEntryMap(), fragment.externalVideoEntryMap());

        boolean isAdmin = authService.isAdmin(headers);

        return collectionEntriesContent
                .data("collection", collection)
                .data("entries", fragment.entries())
                .data("movies", organized.get("movies"))
                .data("seriesList", organized.get("seriesList"))
                .data("seriesEntryMap", fragment.seriesEntryMap())
                .data("heroImageId", fragment.heroImageId())
                .data("isAdmin", isAdmin)
                .data("formatDuration", (Function<Integer, String>) this::formatDuration)
                .render();
    }

    private String formatDuration(Integer s) {
        return s == null ? "0:00" : String.format("%d:%02d", s / 60, s % 60);
    }
}
