package Controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;

@Path("/")
public class NavigationController {
    
    @GET
    public Response serveIndex(@Context UriInfo uriInfo) {
        // Serve the index.html - authentication will be handled by the filter
        try (InputStream is = getClass().getResourceAsStream("/WEB-INF/pages/indexProtected.html")) {
            return Response.ok()
                    .type("text/html")
                    .entity(is)
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Index page not found")
                    .build();
        }
    }
    
    @GET
    @Path("/settings")
    public Response serveSettings() {
        // Serve the settings.html - authentication will be handled by the filter
        try (InputStream is = getClass().getResourceAsStream("/WEB-INF/pages/settings.html")) {
            return Response.ok()
                    .type("text/html")
                    .entity(is)
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Settings page not found")
                    .build();
        }
    }
    
    @GET
    @Path("/import")
    public Response serveImport() {
        // Serve the import.html - authentication will be handled by the filter
        try (InputStream is = getClass().getResourceAsStream("/WEB-INF/pages/import.html")) {
            return Response.ok()
                    .type("text/html")
                    .entity(is)
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Import page not found")
                    .build();
        }
    }
    
    @GET
    @Path("/video")
    public Response serveVideo() {
        // Serve the video.html - authentication will be handled by the filter
        try (InputStream is = getClass().getResourceAsStream("/WEB-INF/pages/video.html")) {
            return Response.ok()
                    .type("text/html")
                    .entity(is)
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Video page not found")
                    .build();
        }
    }
}