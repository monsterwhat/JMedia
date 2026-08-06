package API.Rest;

import API.ApiResponse;
import Models.Settings;
import Models.DTOs.SyncExchangeRequest;
import Models.DTOs.SyncExchangeResponse;
import Services.SettingsService;
import Services.SyncExchangeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

@Path("/api/sync")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SyncExchangeAPI {

    private static final Logger LOGGER = Logger.getLogger(SyncExchangeAPI.class.getName());

    @Inject
    SettingsService settingsService;

    @Inject
    SyncExchangeService syncExchangeService;

    /**
     * Catches any exception that escapes the exchange() try-catch (e.g. Transactional
     * interceptor commit failures after the method returns) and returns a proper
     * ApiResponse.error() instead of Quarkus's generic {"details":"Error id ..."} response.
     */
    @ServerExceptionMapper
    public Response handleUnhandledException(Throwable t) {
        LOGGER.log(Level.SEVERE, "[SyncExchange] Unhandled exception — " + t.getMessage(), t);
        return Response.serverError()
                .entity(ApiResponse.error("Sync internal error: " +
                        (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())))
                .build();
    }

    @GET
    @Path("/ping")
    public Response ping(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        if (!validateApiKey(headers)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("Invalid API key")).build();
        }
        return Response.ok(ApiResponse.success("pong")).build();
    }

    @POST
    @Path("/exchange")
    public Response exchange(SyncExchangeRequest request,
                             @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        LOGGER.info("[SyncExchange] exchange() entered — songs="
                + (request != null && request.songs != null ? request.songs.size() : "null")
                + " videos=" + (request != null && request.videos != null ? request.videos.size() : "null")
                + " collections=" + (request != null && request.collections != null ? request.collections.size() : "null")
                + " type=" + (request != null ? request.syncType : "null"));

        if (!validateApiKey(headers)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("Invalid API key")).build();
        }

        if (request == null) {
            SyncExchangeResponse empty = new SyncExchangeResponse();
            return Response.ok(ApiResponse.success(empty)).build();
        }

        try {
            return Response.ok(syncExchangeService.exchange(request)).build();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[SyncExchange] exchange() failed — " + e.getMessage(), e);
            return Response.serverError()
                    .entity(ApiResponse.error("Sync processing error: " + e.getMessage()))
                    .build();
        }
    }

    private boolean validateApiKey(jakarta.ws.rs.core.HttpHeaders headers) {
        Settings settings = settingsService.getOrCreateSettings();
        String localApiKey = settings.getSyncApiKey();
        if (localApiKey == null || localApiKey.isBlank()) {
            return false;
        }
        String requestKey = headers.getHeaderString("X-JMedia-Sync-Key");
        return localApiKey.equals(requestKey);
    }

}
