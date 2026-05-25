package API.Rest;

import API.ApiResponse;
import Services.CollectionService;
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
    public Response listCollections() {
        return Response.ok(ApiResponse.success(collectionService.listCollections())).build();
    }

    @GET
    @Path("/{id}")
    public Response getCollection(@PathParam("id") Long id) {
        var c = collectionService.getCollection(id);
        if (c == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
        return Response.ok(ApiResponse.success(c)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createCollection(@Context HttpHeaders headers,
                                     @FormParam("name") String name,
                                     @FormParam("description") String description) {
        if (!checkAdmin(headers))
            return Response.status(403).entity(ApiResponse.error("Admin access required")).build();
        if (name == null || name.trim().isEmpty())
            return Response.status(400).entity(ApiResponse.error("Name is required")).build();
        var c = collectionService.create(name.trim(), description);
        return Response.ok(ApiResponse.success(c)).build();
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateCollection(@Context HttpHeaders headers,
                                     @PathParam("id") Long id,
                                     @FormParam("name") String name,
                                     @FormParam("description") String description) {
        if (!checkAdmin(headers))
            return Response.status(403).entity(ApiResponse.error("Admin access required")).build();
        var c = collectionService.update(id, name, description);
        if (c == null) return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
        return Response.ok(ApiResponse.success(c)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteCollection(@Context HttpHeaders headers,
                                     @PathParam("id") Long id) {
        if (!checkAdmin(headers))
            return Response.status(403).entity(ApiResponse.error("Admin access required")).build();
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
                             @FormParam("orderIndex") @DefaultValue("0") int orderIndex,
                             @FormParam("notes") String notes) {
        if (!checkAdmin(headers))
            return Response.status(403).entity(ApiResponse.error("Admin access required")).build();
        if (videoId == null)
            return Response.status(400).entity(ApiResponse.error("videoId is required")).build();
        var e = collectionService.addEntry(id, videoId, orderIndex, notes);
        if (e == null) return Response.status(404).entity(ApiResponse.error("Collection or video not found")).build();
        return Response.ok(ApiResponse.success(Map.of("id", e.id))).build();
    }

    @PUT
    @Path("/entries/{entryId}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateEntry(@Context HttpHeaders headers,
                                @PathParam("entryId") Long entryId,
                                @FormParam("orderIndex") Integer orderIndex,
                                @FormParam("notes") String notes) {
        if (!checkAdmin(headers))
            return Response.status(403).entity(ApiResponse.error("Admin access required")).build();
        var e = collectionService.updateEntry(entryId, orderIndex, notes);
        if (e == null) return Response.status(404).entity(ApiResponse.error("Entry not found")).build();
        return Response.ok(ApiResponse.success(Map.of("id", e.id))).build();
    }

    @DELETE
    @Path("/entries/{entryId}")
    public Response removeEntry(@Context HttpHeaders headers,
                                @PathParam("entryId") Long entryId) {
        if (!checkAdmin(headers))
            return Response.status(403).entity(ApiResponse.error("Admin access required")).build();
        if (collectionService.removeEntry(entryId))
            return Response.ok(ApiResponse.success("Entry removed")).build();
        return Response.status(404).entity(ApiResponse.error("Entry not found")).build();
    }

    @PUT
    @Path("/{id}/entries/reorder")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reorderEntries(@Context HttpHeaders headers,
                                   @PathParam("id") Long id, Map<String, Object> rawMap) {
        if (!checkAdmin(headers))
            return Response.status(403).entity(ApiResponse.error("Admin access required")).build();
        java.util.Map<Long, Integer> orderMap = new java.util.HashMap<>();
        for (var entry : rawMap.entrySet()) {
            try {
                Long entryId = Long.parseLong(entry.getKey());
                Integer order = ((Number) entry.getValue()).intValue();
                orderMap.put(entryId, order);
            } catch (Exception ignored) {}
        }
        if (collectionService.reorderEntries(id, orderMap))
            return Response.ok(ApiResponse.success("Reordered")).build();
        return Response.status(404).entity(ApiResponse.error("Collection not found")).build();
    }
}
