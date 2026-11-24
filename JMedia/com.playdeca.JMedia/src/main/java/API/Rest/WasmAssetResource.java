package API.Rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the JASSUB WebAssembly binaries with {@code application/wasm}.
 * <p>
 * Static resources are handed to Vert.x's {@code StaticHandler}, which takes the
 * content type from {@code MimeMapping}; that table has no {@code .wasm} entry, so
 * the files were served as {@code application/octet-stream} and
 * {@code WebAssembly.instantiateStreaming} refused them ("Response has
 * unsupported MIME type"). Quarkus 3.34 has no supported override for this: the
 * {@code quarkus.http.mime-types.wasm} key is not recognised, and a per-path
 * {@code quarkus.http.filter.*.header."Content-Type"} is overwritten by the static
 * handler. Serving the two files the subtitle renderer needs from a resource with
 * {@code @Produces} is the reliable fix; the nginx front end also maps the
 * extension for anything else.
 * <p>
 * Only known names are exposed, which also rules out path traversal.
 */
@Path("/wasm")
public class WasmAssetResource {

    private static final Logger LOG = LoggerFactory.getLogger(WasmAssetResource.class);
    private static final String BASE = "META-INF/resources/lib/jassub/wasm/";
    private static final Set<String> ALLOWED = Set.of(
            "jassub-worker.wasm",
            "jassub-worker-modern.wasm");

    @GET
    @Path("/{name}")
    @Produces("application/wasm")
    public Response get(@PathParam("name") String name) {
        if (!ALLOWED.contains(name)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(BASE + name)) {
            if (in == null) {
                LOG.warn("WASM asset missing from the classpath: {}{}", BASE, name);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            byte[] bytes = in.readAllBytes();
            return Response.ok(bytes)
                    .type("application/wasm")
                    .header("Cache-Control", "public, max-age=86400")
                    .build();
        } catch (IOException e) {
            LOG.error("Could not read WASM asset {}", name, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }
}
