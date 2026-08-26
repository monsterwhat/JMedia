package Services;

import Models.Video.EpgEntry;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EpgService {

    private static final Logger LOG = LoggerFactory.getLogger(EpgService.class);
    private static final DateTimeFormatter XMLTV_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int BATCH_SIZE = 500;

    @Inject
    @PersistenceUnit("video")
    EntityManager em;

    @Transactional
    public void importXmltv(String xmltvContent) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(xmltvContent)));

            // Parse <channel> elements to build id -> display-name map
            Map<String, String> channelMap = new HashMap<>();
            NodeList channelNodes = doc.getElementsByTagName("channel");
            for (int i = 0; i < channelNodes.getLength(); i++) {
                Element channelElement = (Element) channelNodes.item(i);
                String channelId = channelElement.getAttribute("id");
                String displayName = getChildText(channelElement, "display-name");
                if (channelId != null && !channelId.isEmpty()) {
                    channelMap.put(channelId, displayName);
                }
            }
            LOG.info("Parsed {} EPG channels from XMLTV", channelMap.size());

            // Parse <programme> elements
            NodeList programmeNodes = doc.getElementsByTagName("programme");
            List<EpgEntry> batch = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();

            for (int i = 0; i < programmeNodes.getLength(); i++) {
                Element progElement = (Element) programmeNodes.item(i);

                EpgEntry entry = new EpgEntry();
                entry.epgChannelId = progElement.getAttribute("channel");
                entry.startTime = parseXmltvTime(progElement.getAttribute("start"));
                entry.endTime = parseXmltvTime(progElement.getAttribute("stop"));
                entry.title = getChildText(progElement, "title");
                entry.description = getChildText(progElement, "desc");
                entry.icon = getChildAttribute(progElement, "icon", "src");
                entry.language = getChildText(progElement, "language");
                entry.episode = getChildText(progElement, "episode-num");
                entry.category = getChildText(progElement, "category");
                entry.importedAt = now;

                batch.add(entry);

                if (batch.size() >= BATCH_SIZE) {
                    persistBatch(batch);
                    batch.clear();
                }
            }

            // Persist remaining entries
            if (!batch.isEmpty()) {
                persistBatch(batch);
            }

            LOG.info("Imported {} EPG entries from XMLTV", programmeNodes.getLength());
        } catch (Exception e) {
            LOG.error("Failed to parse XMLTV content: {}", e.getMessage());
            throw new RuntimeException("XMLTV import failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<EpgEntry> findByChannelId(String epgChannelId) {
        return EpgEntry.find("epgChannelId = ?1 ORDER BY startTime", epgChannelId).list();
    }

    @Transactional
    public List<EpgEntry> findCurrentPrograms() {
        LocalDateTime now = LocalDateTime.now();
        return EpgEntry.find("startTime <= ?1 AND endTime > ?1 ORDER BY startTime", now).list();
    }

    @Transactional
    public List<EpgEntry> findUpcoming(String epgChannelId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return EpgEntry.find(
            "epgChannelId = ?1 AND startTime > ?2 ORDER BY startTime",
            epgChannelId, now
        ).page(0, limit).list();
    }

    @Transactional
    public List<EpgEntry> findCurrentAndUpcoming(String epgChannelId, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return EpgEntry.find(
            "epgChannelId = ?1 AND endTime > ?2 ORDER BY startTime",
            epgChannelId, now
        ).page(0, limit).list();
    }

    @Transactional
    public List<EpgEntry> findAllForChannel(String epgChannelId) {
        return EpgEntry.find("epgChannelId = ?1 ORDER BY startTime", epgChannelId).list();
    }

    @Transactional
    public void deleteAll() {
        EpgEntry.deleteAll();
    }

    @Transactional
    public long count() {
        return EpgEntry.count();
    }

    @Transactional
    public long importXmltvReplacingAll(String xmltvContent) {
        deleteAll();
        importXmltv(xmltvContent);
        return count();
    }

    private void persistBatch(List<EpgEntry> batch) {
        for (EpgEntry entry : batch) {
            em.persist(entry);
        }
        em.flush();
        em.clear();
    }

    private LocalDateTime parseXmltvTime(String xmltvTime) {
        if (xmltvTime == null || xmltvTime.isEmpty()) {
            return null;
        }
        try {
            // XMLTV times may carry an explicit offset ("20240101120000 +0200").
            // Normalize everything to UTC: xmltv.php republishes entries as +0000
            // and player_api.php converts them at ZoneOffset.UTC, so storing raw
            // local wall-time shifted the whole guide for non-UTC sources.
            String[] parts = xmltvTime.trim().split("\\s+");
            LocalDateTime local = LocalDateTime.parse(parts[0], XMLTV_FORMAT);
            if (parts.length < 2) {
                return local;
            }
            String offsetText = parts[1].startsWith("GMT") ? parts[1].substring(3) : parts[1];
            java.time.ZoneOffset offset = java.time.ZoneOffset.of(offsetText);
            return local.atOffset(offset)
                    .withOffsetSameInstant(java.time.ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            LOG.warn("Failed to parse XMLTV time: {}", xmltvTime);
            return null;
        }
    }

    private String getChildText(Element parent, String tagName) {
        NodeList nodeList = parent.getElementsByTagName(tagName);
        if (nodeList != null && nodeList.getLength() > 0) {
            return nodeList.item(0).getTextContent();
        }
        return null;
    }

    private String getChildAttribute(Element parent, String tagName, String attrName) {
        NodeList nodeList = parent.getElementsByTagName(tagName);
        if (nodeList != null && nodeList.getLength() > 0) {
            Element child = (Element) nodeList.item(0);
            return child.getAttribute(attrName);
        }
        return null;
    }
}
