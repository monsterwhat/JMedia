package API.Rest;

import API.ApiResponse;
import Models.Profile;
import Services.CollectionService;
import Services.SettingsService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/collections")
@Produces(MediaType.APPLICATION_JSON)
public class CollectionApi {

    @Inject
    CollectionService collectionService;

    @Inject
    SettingsService settingsService;

    private boolean checkAdmin(HttpHeaders headers) {
        String sessionId = null;
        if (headers.getCookies() != null && headers.getCookies().containsKey("JMEDIA_SESSION")) {
            sessionId = headers.getCookies().get("JMEDIA_SESSION").getValue();
        }
        if (sessionId == null) return false;
        Models.Session session = Models.Session.findBySessionId(sessionId);
        if (session == null || !session.active) return false;
        Models.User user = Models.User.find("username", session.username).firstResult();
        return user != null && "admin".equals(user.getGroupName());
    }

    @GET
    public Response listCollections(@Context HttpHeaders headers) {
        boolean isAdmin = checkAdmin(headers);
        Profile activeProfile = settingsService.getActiveProfile();
        return Response.ok(ApiResponse.success(collectionService.listCollections(activeProfile, isAdmin))).build();
    }

    @GET
    @Path("/my")
    public Response listMyCollections(@Context HttpHeaders headers) {
        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null)
            return Response.status(401).entity(ApiResponse.error("No active profile")).build();
        return Response.ok(ApiResponse.success(collectionService.findMyCollections(activeProfile))).build();
    }

    @GET
    @Path("/{id}")
    public Response getCollection(@Context HttpHeaders headers, @PathParam("id") Long id) {
        var c = collectionService.getCollection(id);
        if (c == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
        boolean isAdmin = checkAdmin(headers);
        Profile activeProfile = settingsService.getActiveProfile();
        if (c.profile != null && !c.isPublic && !isAdmin
                && (activeProfile == null || !activeProfile.equals(c.profile))) {
            return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
        }
        return Response.ok(ApiResponse.success(c)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createCollection(@Context HttpHeaders headers,
                                     @FormParam("name") String name,
                                     @FormParam("description") String description,
                                     @FormParam("isPublic") @DefaultValue("false") boolean isPublic) {
        boolean isAdmin = checkAdmin(headers);
        if (name == null || name.trim().isEmpty())
            return Response.status(400).entity(ApiResponse.error("Name is required")).build();

        if (isAdmin) {
            var c = collectionService.create(name.trim(), description);
            return Response.ok(ApiResponse.success(c)).build();
        }

        Profile activeProfile = settingsService.getActiveProfile();
        if (activeProfile == null)
            return Response.status(401).entity(ApiResponse.error("No active profile")).build();
        var c = collectionService.create(name.trim(), description, activeProfile, isPublic);
        return Response.ok(ApiResponse.success(c)).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateCollection(@Context HttpHeaders headers,
                                     @PathParam("id") Long id,
                                     @FormParam("name") String name,
                                     @FormParam("description") String description) {
        boolean isAdmin = checkAdmin(headers);
        var c = collectionService.getCollection(id);
        if (c == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();

        boolean isOwner = c.profile != null && c.profile.equals(settingsService.getActiveProfile());
        if (!isAdmin && !isOwner)
            return Response.status(403).entity(ApiResponse.error("Access denied")).build();

        var updated = collectionService.update(id, name, description);
        if (updated == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
        return Response.ok(ApiResponse.success(updated)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteCollection(@Context HttpHeaders headers,
                                     @PathParam("id") Long id) {
        boolean isAdmin = checkAdmin(headers);
        var c = collectionService.getCollection(id);
        if (c == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();

        boolean isOwner = c.profile != null && c.profile.equals(settingsService.getActiveProfile());
        if (!isAdmin && !isOwner)
            return Response.status(403).entity(ApiResponse.error("Access denied")).build();

        if (collectionService.delete(id))
            return Response.ok(ApiResponse.success("Collection deleted")).build();
        return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
    }

    @GET
    @Path("/{id}/entries")
    public Response getEntries(@PathParam("id") Long id) {
        var entries = collectionService.getEntries(id);
        return Response.ok(ApiResponse.success(entries)).build();
    }

    @POST
    @Path("/{id}/entries")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addEntry(@Context HttpHeaders headers,
                             @PathParam("id") Long id,
                             @FormParam("videoId") Long videoId,
                             @FormParam("externalVideoId") Long externalVideoId,
                             @FormParam("orderIndex") @DefaultValue("0") int orderIndex,
                             @FormParam("notes") String notes) {
        boolean isAdmin = checkAdmin(headers);
        var c = collectionService.getCollection(id);
        if (c == null)
            return Response.status(404).entity(ApiResponse.error("Collection not found")).build();

        boolean isOwner = c.profile != null && c.profile.equals(settingsService.getActiveProfile());
        if (!isAdmin && !isOwner)
            return Response.status(403).entity(ApiResponse.error("Access denied")).build();

        if (videoId == null && externalVideoId == null)
            return Response.status(400).entity(ApiResponse.error("videoId or externalVideoId is required")).build();

        if (videoId != null) {
            var e = collectionService.addEntry(id, videoId, orderIndex, notes);
            if (e == null)
                return Response.status(404).entity(ApiResponse.error("Collection or video not found")).build();
            collectionService.updateCoverVideo(c);
            return Response.ok(ApiResponse.success(Map.of("id", e.id))).build();
        } else {
            var e = collectionService.addEntryWithExternalVideo(id, externalVideoId, orderIndex, notes);
            if (e == null)
                return Response.status(404).entity(ApiResponse.error("Collection or external video not found")).build();
            return Response.ok(ApiResponse.success(Map.of("id", e.id))).build();
        }
    }

    @PUT
    @Path("/entries/{entryId}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateEntry(@Context HttpHeaders headers,
                                @PathParam("entryId") Long entryId,
                                @FormParam("orderIndex") Integer orderIndex,
                                @FormParam("notes") String notes) {
        boolean isAdmin = checkAdmin(headers);
        Models.CollectionEntry foundEntry = Models.CollectionEntry.findById(entryId);
        if (foundEntry == null)
            return Response.status(404).entity(ApiResponse.error("Entry not found")).build();

        boolean isOwner = foundEntry.collection.profile != null
            && foundEntry.collection.profile.equals(settingsService.getActiveProfile());
        if (!isAdmin && !isOwner)
            return Response.status(403).entity(ApiResponse.error("Access denied")).build();

        var e = collectionService.updateEntry(entryId, orderIndex, notes);
        if (e == null) return Response.status(404).entity(ApiResponse.error("Entry not found")).build();
        return Response.ok(ApiResponse.success(Map.of("id", e.id))).build();
    }

    @DELETE
    @Path("/entries/{entryId}")
    public Response removeEntry(@Context HttpHeaders headers,
                                @PathParam("entryId") Long entryId) {
        boolean isAdmin = checkAdmin(headers);
        Models.CollectionEntry foundEntry = Models.CollectionEntry.findById(entryId);
        if (foundEntry == null)
            return Response.status(404).entity(ApiResponse.error("Entry not found")).build();

        boolean isOwner = foundEntry.collection.profile != null
            && foundEntry.collection.profile.equals(settingsService.getActiveProfile());
        if (!isAdmin && !isOwner)
            return Response.status(403).entity(ApiResponse.error("Access denied")).build();

        if (collectionService.removeEntry(entryId)) {
            collectionService.updateCoverVideo(foundEntry.collection);
            return Response.ok(ApiResponse.success("Entry removed")).build();
        }
        return Response.status(404).entity(ApiResponse.error("Entry not found")).build();
    }

    @PUT
    @Path("/{id}/entries/reorder")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reorderEntries(@Context HttpHeaders headers,
                                   @PathParam("id") Long id, Map<String, Object> rawMap) {
        boolean isAdmin = checkAdmin(headers);
        var c = collectionService.getCollection(id);
        if (c == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();

        boolean isOwner = c.profile != null && c.profile.equals(settingsService.getActiveProfile());
        if (!isAdmin && !isOwner)
            return Response.status(403).entity(ApiResponse.error("Access denied")).build();

        java.util.Map<Long, Integer> orderMap = new java.util.HashMap<>();
        for (var entry : rawMap.entrySet()) {
            try {
                Long eid = Long.parseLong(entry.getKey());
                Integer order = ((Number) entry.getValue()).intValue();
                orderMap.put(eid, order);
            } catch (Exception ignored) {}
        }
        if (collectionService.reorderEntries(id, orderMap))
            return Response.ok(ApiResponse.success("Reordered")).build();
        return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
    }
}
