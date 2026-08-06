package Services;

import Models.SyncLog;
import Models.SyncServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class SyncServerService {

    @PersistenceContext
    EntityManager em;

    @Transactional
    public SyncServer addServer(SyncServer input) {
        input.url = normalizeUrl(input.url);
        input.enabled = true;
        em.persist(input);
        return input;
    }

    @Transactional
    public SyncServer updateServer(Long id, SyncServer input) {
        SyncServer server = SyncServer.findById(id);
        if (server == null) {
            return null;
        }

        if (input.url != null) {
            input.url = normalizeUrl(input.url);
        }
        if (input.name != null) server.name = input.name;
        if (input.url != null) server.url = input.url;
        if (input.apiKey != null) server.apiKey = input.apiKey;
        server.enabled = input.enabled;

        em.merge(server);
        return server;
    }

    @Transactional
    public boolean deleteServer(Long id) {
        SyncServer server = SyncServer.findById(id);
        if (server == null) {
            return false;
        }

        SyncLog.delete("server.id", id);
        server.delete();
        return true;
    }

    public static String normalizeUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        return url.replaceAll("/+$", "");
    }

}
