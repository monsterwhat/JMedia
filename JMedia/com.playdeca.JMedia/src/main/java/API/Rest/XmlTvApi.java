package API.Rest;

import Models.Video.EpgEntry;
import Models.Video.LiveChannel;
import Models.Settings.User;
import Services.AuthService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Path("/xmltv.php")
@Produces("application/xml")
public class XmlTvApi {

    @Inject
    AuthService authService;

    private static final DateTimeFormatter XMLTV_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @GET
    public Response generateXmlTv(
            @QueryParam("username") String username,
            @QueryParam("password") String password) {

        if (username == null || password == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        Optional<User> userOpt = authService.authenticate(username, password);
        if (userOpt.isEmpty()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        List<LiveChannel> channels = LiveChannel.listAll();
        List<EpgEntry> epgEntries = EpgEntry.listAll();

        Set<String> channelIds = new HashSet<>();
        for (EpgEntry entry : epgEntries) {
            if (entry.epgChannelId != null && !entry.epgChannelId.isBlank()) {
                channelIds.add(entry.epgChannelId);
            }
        }
        // Also add channels that have a tvgId even without EPG data
        for (LiveChannel ch : channels) {
            if (ch.tvgId != null && !ch.tvgId.isBlank()) {
                channelIds.add(ch.tvgId);
            }
        }

        try {
            String xml = buildXmlTv(channels, epgEntries, channelIds);
            return Response.ok(xml, "application/xml").build();
        } catch (Exception e) {
            return Response.serverError()
                    .entity("{\"error\":\"Failed to generate XMLTV: " + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    private String buildXmlTv(List<LiveChannel> channels, List<EpgEntry> epgEntries,
                              Set<String> channelIds) throws Exception {
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        StringWriter writer = new StringWriter();
        XMLStreamWriter xml = factory.createXMLStreamWriter(writer);

        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeCharacters("\n");
        xml.writeStartElement("tv");
        xml.writeAttribute("generator-info-name", "JMedia");
        xml.writeCharacters("\n");

        // Write channel elements from EpgEntry channel IDs
        for (String channelId : channelIds) {
            String displayName = findChannelName(channels, channelId);
            String logoUrl = findChannelLogo(channels, channelId);

            xml.writeCharacters("  ");
            xml.writeStartElement("channel");
            xml.writeAttribute("id", channelId);
            xml.writeCharacters("\n");

            xml.writeCharacters("    ");
            xml.writeStartElement("display-name");
            xml.writeCharacters(displayName);
            xml.writeEndElement();
            xml.writeCharacters("\n");

            if (logoUrl != null && !logoUrl.isBlank()) {
                xml.writeCharacters("    ");
                xml.writeStartElement("icon");
                xml.writeAttribute("src", logoUrl);
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }

            xml.writeCharacters("  ");
            xml.writeEndElement();
            xml.writeCharacters("\n");
        }

        // Write programme elements from EPG entries
        for (EpgEntry entry : epgEntries) {
            if (entry.epgChannelId == null || entry.epgChannelId.isBlank()) {
                continue;
            }
            if (entry.startTime == null || entry.endTime == null) {
                continue;
            }

            xml.writeCharacters("  ");
            xml.writeStartElement("programme");
            xml.writeAttribute("start", formatXmltvTime(entry.startTime) + " +0000");
            xml.writeAttribute("stop", formatXmltvTime(entry.endTime) + " +0000");
            xml.writeAttribute("channel", entry.epgChannelId);
            xml.writeCharacters("\n");

            if (entry.title != null && !entry.title.isBlank()) {
                xml.writeCharacters("    ");
                xml.writeStartElement("title");
                xml.writeCharacters(entry.title);
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }

            if (entry.description != null && !entry.description.isBlank()) {
                xml.writeCharacters("    ");
                xml.writeStartElement("desc");
                xml.writeCharacters(entry.description);
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }

            if (entry.icon != null && !entry.icon.isBlank()) {
                xml.writeCharacters("    ");
                xml.writeStartElement("icon");
                xml.writeAttribute("src", entry.icon);
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }

            if (entry.language != null && !entry.language.isBlank()) {
                xml.writeCharacters("    ");
                xml.writeStartElement("language");
                xml.writeCharacters(entry.language);
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }

            if (entry.category != null && !entry.category.isBlank()) {
                xml.writeCharacters("    ");
                xml.writeStartElement("category");
                xml.writeCharacters(entry.category);
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }

            if (entry.episode != null && !entry.episode.isBlank()) {
                xml.writeCharacters("    ");
                xml.writeStartElement("episode-num");
                xml.writeAttribute("system", "onscreen");
                xml.writeCharacters(entry.episode);
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }

            xml.writeCharacters("  ");
            xml.writeEndElement();
            xml.writeCharacters("\n");
        }

        xml.writeEndElement();
        xml.writeCharacters("\n");
        xml.writeEndDocument();

        xml.flush();
        xml.close();
        return writer.toString();
    }

    private String findChannelName(List<LiveChannel> channels, String tvgId) {
        for (LiveChannel ch : channels) {
            if (tvgId.equals(ch.tvgId)) {
                return ch.name != null ? ch.name : tvgId;
            }
        }
        return tvgId;
    }

    private String findChannelLogo(List<LiveChannel> channels, String tvgId) {
        for (LiveChannel ch : channels) {
            if (tvgId.equals(ch.tvgId)) {
                return ch.logoUrl;
            }
        }
        return null;
    }

    private String formatXmltvTime(LocalDateTime dateTime) {
        return dateTime.format(XMLTV_FORMAT);
    }
}
