package Controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path; 
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream; 
import org.jboss.logging.Logger;

@Path("/")
public class NavigationController {
    
    private static final Logger logger = Logger.getLogger(NavigationController.class);
    
    @GET
    public Response serveIndex(@Context UriInfo uriInfo) {
        return servePage("indexProtected.html", "Index page");
    }
    
    @GET
    @Path("/settings")
    public Response serveSettings() {
        return servePage("settings.html", "Settings page");
    }
    
    @GET
    @Path("/import")
    public Response serveImport() {
        return servePage("import.html", "Import page");
    }
    
    @GET
    @Path("/video")
    public Response serveVideo() {
        return servePage("video.html", "Video page");
    }
    
    @GET
    @Path("/setup")
    public Response serveSetup() {
        return servePage("setup.html", "Setup page");
    }
    
    @GET
    @Path("/classic")
    public Response serveClassic() {
        return servePage("classic.html", "Classic page");
    }
    
    /**
     * Robust resource loading that works in both dev and production environments.
     * Tries multiple classloaders and path formats to find the resource.
     */
    private InputStream loadResourceRobustly(String filename) {
        // Try all possible resource paths and loaders
        String[] possiblePaths = {
            "/WEB-INF/pages/" + filename,           // Current structure (production target)
            "WEB-INF/pages/" + filename,              // Try without leading slash
            "/META-INF/resources/" + filename,        // Fallback to Quarkus standard
            "META-INF/resources/" + filename,         // Fallback without leading slash
            filename                                   // Direct path as last resort
        };
        
        for (String path : possiblePaths) {
            try {
                InputStream is = this.getClass().getResourceAsStream(path);
                if (is != null) {
                    logger.info("✅ Successfully loaded '" + filename + "' from path: '" + path + "'");
                    return is;
                }
                logger.debug("❌ Path '" + path + "' returned null for resource '" + filename + "'");
                
                // Try context class loader for this path
                is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path.startsWith("/") ? path.substring(1) : path);
                if (is != null) {
                    logger.info("✅ Successfully loaded '" + filename + "' via context classloader from path: '" + path + "'");
                    return is;
                }
                
            } catch (Exception e) {
                logger.warn("⚠️ Exception accessing path '" + path + "' for resource '" + filename + "': " + e.getMessage());
            }
        }
        
        logger.error("🚫 Failed to load '" + filename + "' from all attempted paths and loaders");
        return null;
    }
    
    private Response servePage(String fileName, String pageName) {
        String resourceFile = fileName;
        
        logger.info("🔍 Request for page '" + pageName + "' (file: '" + resourceFile + "')");
        
        try {
            InputStream is = loadResourceRobustly(resourceFile);
            
            if (is == null) {
                logger.error("❌ Resource not found for page '" + pageName + "' - returning 404");
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Page not found: " + pageName)
                        .type("text/html")
                        .build();
            }
            
            logger.info("✅ Successfully serving page '" + pageName + "'");
            return Response.ok()
                    .type("text/html")
                    .entity(is)
                    .build();
                    
        } catch (Exception e) {
            logger.error("💥 Error serving page '" + pageName + "': " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Server error while loading " + pageName + ": " + e.getMessage())
                    .type("text/html")
                    .build();
        }
    }
}
