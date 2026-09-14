package Services;

import Models.Settings.XtreamSession;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class XtreamSessionService {

    private static final Logger LOG = LoggerFactory.getLogger(XtreamSessionService.class);
    private static final long STALE_LIVE_SECONDS = 180;
    private static final long SAFETY_STALE_SECONDS = 600;
    private static final long PRUNE_AGE_DAYS = 30;

    @Transactional
    public XtreamSession startSession(String type, String username, String userId, Long streamId, String ext, String ipAddress) {
        XtreamSession session = new XtreamSession();
        session.sessionId = UUID.randomUUID().toString();
        session.userId = userId;
        session.username = username;
        session.type = type;
        session.streamId = streamId;
        session.ext = ext;
        session.ipAddress = ipAddress;
        session.startedAt = Instant.now();
        session.lastActivity = Instant.now();
        session.active = true;
        session.durationSeconds = 0;
        session.persist();
        LOG.info("Xtream session started: sessionId={} type={} streamId={} user={} ip={}",
                session.sessionId, type, streamId, username, ipAddress);
        return session;
    }

    @Transactional
    public void endSession(XtreamSession session, String reason) {
        if (session == null) {
            return;
        }
        if (session.endedAt != null) {
            LOG.debug("Xtream session {} already ended, ignoring end with reason {}", session.sessionId, reason);
            return;
        }
        Instant now = Instant.now();
        session.endedAt = now;
        session.durationSeconds = ChronoUnit.SECONDS.between(session.startedAt, now);
        session.endReason = reason;
        session.active = false;
        session.lastActivity = now;
        session.persist();
        LOG.info("Xtream session ended: sessionId={} duration={}s reason={}", session.sessionId, session.durationSeconds, reason);
    }

    @Transactional
    public void endByDeviceToken(String deviceToken, String reason) {
        if (deviceToken == null || !deviceToken.startsWith("xtream:")) {
            return;
        }
        String sessionId = deviceToken.substring(7);
        XtreamSession session = XtreamSession.findBySessionId(sessionId);
        if (session == null) {
            LOG.debug("No Xtream session record for deviceToken {}", deviceToken);
            return;
        }
        endSession(session, reason);
    }

    @Transactional
    public void bumpLiveActivity(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        XtreamSession session = XtreamSession
                .find("username = ?1 and type = 'live' and active = true order by startedAt desc")
                .firstResult();
        if (session != null) {
            session.lastActivity = Instant.now();
            session.persist();
        }
    }

    @Transactional
    public List<XtreamSession> listRecent(int limit) {
        return XtreamSession.find("order by startedAt desc").page(0, limit).list();
    }

    @Transactional
    public long clearHistory() {
        long count = XtreamSession.deleteAll();
        LOG.info("Cleared Xtream session history: {} records", count);
        return count;
    }

    @Scheduled(every = "60s")
    @Transactional
    public void closeStaleSessions() {
        List<XtreamSession> active = XtreamSession.find("active = true").list();
        Instant now = Instant.now();
        int closed = 0;
        for (XtreamSession s : active) {
            if (s.lastActivity == null) {
                continue;
            }
            if ("live".equals(s.type)) {
                if (s.lastActivity.plusSeconds(STALE_LIVE_SECONDS).isBefore(now)) {
                    endSession(s, "disconnected");
                    closed++;
                }
            } else {
                if (s.lastActivity.plusSeconds(SAFETY_STALE_SECONDS).isBefore(now)) {
                    endSession(s, "timeout");
                    closed++;
                }
            }
        }
        if (closed > 0) {
            LOG.info("Closed {} stale Xtream sessions", closed);
        }
    }

    @Scheduled(every = "12h")
    @Transactional
    public void pruneExpiredSessions() {
        Instant cutoff = Instant.now().minus(PRUNE_AGE_DAYS, ChronoUnit.DAYS);
        long ended = XtreamSession.delete("endedAt != null and endedAt < ?1", cutoff);
        long zombies = XtreamSession.delete("endedAt is null and startedAt < ?1", cutoff);
        if (ended > 0 || zombies > 0) {
            LOG.info("Pruned Xtream sessions: {} ended, {} zombie active", ended, zombies);
        }
    }
}