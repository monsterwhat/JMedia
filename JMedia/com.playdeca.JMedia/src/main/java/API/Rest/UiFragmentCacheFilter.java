package API.Rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * UI fragments are rendered per request and embed versioned script URLs plus
 * user/profile-specific data. Letting the browser cache them means a tab can keep
 * loading the previous build's player scripts after an update (which is exactly
 * how a fixed player can appear to still be broken). Mark them no-store.
 */
@Provider
public class UiFragmentCacheFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path != null && path.startsWith("api/video/ui/")) {
            response.getHeaders().putSingle("Cache-Control", "no-store");
        }
    }
}
