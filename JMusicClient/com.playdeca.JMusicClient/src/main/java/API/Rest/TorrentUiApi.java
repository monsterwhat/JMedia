package API.Rest;

import Controllers.TorrentController;
import Models.Torrents.Core.Torrent;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template; 
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.util.UUID;

@Path("/ui/torrents")
public class TorrentUiApi {

    @Inject
    @Location("torrentListFragment.html")
    Template torrentListFragment;

    @Inject
    @Location("createTorrentFormFragment.html")
    Template createTorrentFormFragment;

    @Inject
    @Location("editTorrentFormFragment.html")
    Template editTorrentFormFragment;

    @Inject
    TorrentController torrentController;

    @GET
    @Path("/list")
    @Produces(MediaType.TEXT_HTML)
    public String getTorrentList(@QueryParam("page") @DefaultValue("0") int pageNumber, @QueryParam("size") @DefaultValue("10") int pageSize, @QueryParam("tag") String tag, @QueryParam("sortBy") String sortBy, @QueryParam("order") String order) {
        Models.DTOs.PaginatedTorrents paginatedTorrents = torrentController.listAllTorrentsPaginatedAndFiltered(pageNumber, pageSize, tag, sortBy, order);
        long totalPages = (long) Math.ceil((double) paginatedTorrents.totalCount() / pageSize);
        long startIndex = (long) pageNumber * pageSize;
        return torrentListFragment.data("torrents", paginatedTorrents.torrents())
                                  .data("currentPage", pageNumber)
                                  .data("pageSize", pageSize)
                                  .data("totalCount", paginatedTorrents.totalCount())
                                  .data("currentTag", tag != null ? tag : "")
                                  .data("sortBy", sortBy != null ? sortBy : "")
                                  .data("order", order != null ? order : "")
                                  .data("totalPages", totalPages)
                                  .data("startIndex", startIndex)
                                  .render();
    }

    @GET
    @Path("/create-form")
    @Produces(MediaType.TEXT_HTML)
    public String getCreateTorrentForm() {
        return createTorrentFormFragment.data("creatorId", "").render(); // Placeholder for creatorId
    }

    @GET
    @Path("/edit-form/{id}")
    @Produces(MediaType.TEXT_HTML)
    public String getEditTorrentForm(@PathParam("id") UUID id) {
        Torrent torrent = torrentController.getTorrent(id);
        if (torrent == null) {
            // Handle not found case, perhaps return an error fragment or empty string
            return "<p class=\"has-text-danger\">Torrent not found for editing.</p>";
        }
        return editTorrentFormFragment.data("torrent", torrent).render();
    }
}
