package API.Rest;

import Services.EpgService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Path("/api/epg")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EpgApi {

    @Inject
    EpgService epgService;

    private final ObjectMapper mapper = new ObjectMapper();

    @POST
    @Path("/import")
    public Response importXmltv(JsonNode body) {
        String xmltvContent = body.has("xmltv") ? body.get("xmltv").asText(null) : null;
        String xmltvUrl = body.has("url") ? body.get("url").asText(null) : null;
        
        if ((xmltvContent == null || xmltvContent.isBlank()) && (xmltvUrl == null || xmltvUrl.isBlank())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Either 'xmltv' content or 'url' is required\"}")
                    .build();
        }
        
        // If URL provided, fetch the XMLTV content
        if (xmltvUrl != null && !xmltvUrl.isBlank()) {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(xmltvUrl))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .build();
                java.net.http.HttpResponse<String> response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                xmltvContent = response.body();
            } catch (Exception e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Failed to fetch XMLTV from URL: " + e.getMessage().replace("\"", "'") + "\"}")
                        .build();
            }
        }
        
        try {
            long count = epgService.importXmltvReplacingAll(xmltvContent);
            return Response.ok("{\"success\":true,\"count\":" + count + "}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}")
                    .build();
        }
    }

    @GET
    @Path("/status")
    public Response getStatus() {
        long count = epgService.count();
        return Response.ok("{\"epg_count\":" + count + "}").build();
    }
}
