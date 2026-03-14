package Services;

import Models.SubtitleTrack;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SubtitleFormatConverter {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SubtitleFormatConverter.class);
    private static final Pattern ASS_HEADER_PATTERN = Pattern.compile("^\\[V4\\+? Styles\\+?\\]");
    private static final Pattern ASS_DIALOGUE_PATTERN = Pattern.compile("^Dialogue:");
    private static final Pattern SRT_TIMESTAMP_PATTERN = 
        Pattern.compile("(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{1,2})[\\,\\.](\\d{1,3})\\s*-+>\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{1,2})[\\,\\.](\\d{1,3})");
    
    public String convertToWebVTT(SubtitleTrack track) throws IOException {
        switch (track.format.toLowerCase()) {
            case "srt":
                return convertSRTToWebVTT(track);
            case "ass":
            case "ssa":
                return convertASSToWebVTT(track);
            case "vtt":
                return readWebVTT(track);
            default:
                throw new UnsupportedOperationException("Unsupported subtitle format: " + track.format);
        }
    }
    
    /**
     * Shifts all timestamps in a WebVTT string by a specified offset in seconds.
     */
    public String applyOffset(String vttContent, double offsetSeconds) {
        if (offsetSeconds <= 0 || vttContent == null) return vttContent;
        
        vttContent = vttContent.replace("\r\n", "\n").replace("\r", "\n");
        
        StringBuilder result = new StringBuilder();
        
        // Split by double newline to get individual subtitle blocks
        String[] blocks = vttContent.split("\\n\\n+");
        
        boolean headerAdded = false;
        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty()) continue;
            
            // Check if this is the header block (WEBVTT ...)
            if (block.startsWith("WEBVTT") || block.startsWith("NOTE") || block.startsWith("STYLE") || block.startsWith("REGION")) {
                result.append(block).append("\n\n");
                if (block.startsWith("WEBVTT")) headerAdded = true;
                continue;
            }
            
            String[] lines = block.split("\\n");
            String timestampLine = null;
            int timestampLineIdx = -1;
            
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(" --> ")) {
                    timestampLine = lines[i];
                    timestampLineIdx = i;
                    break;
                }
            }
            
            // If it's a cue block (has a timestamp)
            if (timestampLine != null) {
                String[] times = timestampLine.split(" --> ");
                if (times.length == 2) {
                    double start = parseVttTimeToSeconds(times[0]);
                    double end = parseVttTimeToSeconds(times[1]);
                    
                    // If the subtitle ends before our seek point, discard it
                    if (end <= offsetSeconds) continue;
                    
                    // Shift times
                    double newStart = Math.max(0, start - offsetSeconds);
                    double newEnd = Math.max(0, end - offsetSeconds);
                    
                    String newTimestampLine = formatSecondsToVtt(newStart) + " --> " + formatSecondsToVtt(newEnd);
                    
                    // Rebuild block
                    for (int i = 0; i < lines.length; i++) {
                        if (i == timestampLineIdx) {
                            result.append(newTimestampLine).append("\n");
                        } else {
                            result.append(lines[i]).append("\n");
                        }
                    }
                    result.append("\n");
                }
            } else {
                // Not a header and not a cue? Preserve it just in case
                result.append(block).append("\n\n");
            }
        }
        
        // Ensure we have a valid header if it was somehow lost
        if (!headerAdded) {
            return "WEBVTT\n\n" + result.toString();
        }
        
        return result.toString();
    }

    private double parseVttTimeToSeconds(String timestamp) {
        try {
            timestamp = timestamp.trim();
            String[] parts = timestamp.split(":");
            if (parts.length == 3) {
                // HH:MM:SS.mmm
                return (Double.parseDouble(parts[0]) * 3600) + 
                       (Double.parseDouble(parts[1]) * 60) + 
                       Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                // MM:SS.mmm
                return (Double.parseDouble(parts[0]) * 60) + 
                       Double.parseDouble(parts[1]);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String formatSecondsToVtt(double seconds) {
        int h = (int) (seconds / 3600);
        int m = (int) ((seconds % 3600) / 60);
        double s = seconds % 60;
        return String.format("%02d:%02d:%06.3f", h, m, s).replace(',', '.');
    }

    private String convertSRTToWebVTT(SubtitleTrack track) throws IOException {
        String content = readSubtitleFile(track.fullPath);
        if (content == null || content.trim().isEmpty()) return "WEBVTT\n\nNOTE Empty file\n";

        StringBuilder webVTT = new StringBuilder();
        webVTT.append("WEBVTT\n\n");

        // Normalize line endings and remove BOM
        content = content.replace("\uFEFF", "");
        content = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = content.split("\n");

        boolean inCue = false;
        StringBuilder cueText = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                if (inCue) {
                    webVTT.append(cueText.toString().trim()).append("\n\n");
                    cueText.setLength(0);
                    inCue = false;
                }
                continue;
            }

            Matcher matcher = SRT_TIMESTAMP_PATTERN.matcher(line);
            if (matcher.find()) {
                if (inCue) {
                    webVTT.append(cueText.toString().trim()).append("\n\n");
                    cueText.setLength(0);
                }
                
                String startTime = convertSRTTimeToWebVTT(
                    matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
                String endTime = convertSRTTimeToWebVTT(
                    matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
                
                webVTT.append(startTime).append(" --> ").append(endTime).append("\n");
                inCue = true;
            } else if (inCue) {
                cueText.append(convertSRTFormattingToWebVTT(line)).append("\n");
            }
        }

        if (inCue && cueText.length() > 0) {
            webVTT.append(cueText.toString().trim()).append("\n\n");
            inCue = false;
        }
        
        if (webVTT.toString().trim().equals("WEBVTT")) {
            webVTT.append("NOTE No subtitle cues were found or parsed from the source file.\n");
            webVTT.append("NOTE Content length: ").append(content.length()).append("\n");
            String preview = content.length() > 100 ? content.substring(0, 100) : content;
            webVTT.append("NOTE Content preview: ").append(preview.replace("\n", " [NL] ")).append("\n");
        }

        return webVTT.toString();
    }
    
    private String convertASSToWebVTT(SubtitleTrack track) throws IOException {
        StringBuilder webVTT = new StringBuilder();
        webVTT.append("WEBVTT\n\n");
        
        String content = readSubtitleFile(track.fullPath);
        String[] lines = content.split("\\r?\\n");
        
        boolean inStylesSection = false;
        boolean inEventsSection = false;
        Map<String, String> styles = new HashMap<>();
        
        // Extract styles from ASS header
        for (String line : lines) {
            line = line.trim();
            
            if (line.startsWith("[V4+ Styles]")) {
                inStylesSection = true;
                webVTT.append("STYLE\n");
                continue;
            } else if (line.startsWith("[Events]")) {
                inEventsSection = true;
                inStylesSection = false;
                continue;
            } else if (line.startsWith("Format:")) {
                continue; // Format definition
            } else if (inStylesSection && line.startsWith("Style:")) {
                parseASSStyle(line, styles);
                // Convert ASS style to WebVTT CSS
                webVTT.append(convertASStyleToWebVTTCSS(line, styles));
            } else if (inEventsSection && line.startsWith("Dialogue:")) {
                // Convert ASS dialogue to WebVTT cue
                String webVTTDialogue = convertASSDialogueToWebVTT(line, styles);
                webVTT.append(webVTTDialogue).append("\n\n");
            }
        }
        
        return webVTT.toString();
    }
    
    private String readWebVTT(SubtitleTrack track) throws IOException {
        String content = readSubtitleFile(track.fullPath);
        // Validate that it's proper WebVTT
        if (content.startsWith("WEBVTT")) {
            return content;
        }
        
        // If it doesn't start with WEBVTT, treat as SRT and convert
        return convertSRTToWebVTT(track);
    }
    
    private String readSubtitleFile(String fullPath) throws IOException {
        byte[] fileBytes = Files.readAllBytes(Path.of(fullPath));
        if (fileBytes.length < 4) return "";

        // Check for ZIP magic number (PK..)
        if (fileBytes[0] == 0x50 && fileBytes[1] == 0x4B && fileBytes[2] == 0x03 && fileBytes[3] == 0x04) {
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new ByteArrayInputStream(fileBytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName().toLowerCase();
                    if (name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".vtt")) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) baos.write(buffer, 0, len);
                        fileBytes = baos.toByteArray();
                        break;
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to extract ZIP subtitle: " + e.getMessage());
            }
        } 
        // Check for GZIP magic number
        else if (fileBytes[0] == (byte)0x1F && fileBytes[1] == (byte)0x8B) {
            try (java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(fileBytes))) {
                fileBytes = gzis.readAllBytes();
            } catch (Exception e) {
                LOGGER.error("Failed to extract GZIP subtitle: " + e.getMessage());
            }
        }
        
        Charset encoding = detectEncoding(fileBytes);
        return new String(fileBytes, encoding);
    }
    
    private Charset detectEncoding(byte[] fileBytes) {
        if (fileBytes.length >= 3 && 
            (fileBytes[0] & 0xFF) == 0xEF && 
            (fileBytes[1] & 0xFF) == 0xBB && 
            (fileBytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        
        // Check if it's UTF-16 LE/BE
        if (fileBytes.length >= 2) {
            if ((fileBytes[0] & 0xFF) == 0xFF && (fileBytes[1] & 0xFF) == 0xFE) return StandardCharsets.UTF_16LE;
            if ((fileBytes[0] & 0xFF) == 0xFE && (fileBytes[1] & 0xFF) == 0xFF) return StandardCharsets.UTF_16BE;
        }

        // Heuristic: If we find many invalid UTF-8 sequences, it's likely Windows-1252 / Latin-1
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(fileBytes));
            return StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException e) {
            // Fallback to common encoding for older subtitle files
            return Charset.forName("windows-1252");
        }
    }
    
    private String convertSRTTimeToWebVTT(String hours, String minutes, String seconds, String milliseconds) {
        int h = (hours == null || hours.isEmpty()) ? 0 : Integer.parseInt(hours);
        int m = Integer.parseInt(minutes);
        int s = Integer.parseInt(seconds);
        
        // Handle variations in millisecond digits (1-3 digits)
        int ms = Integer.parseInt(milliseconds);
        if (milliseconds.length() == 1) ms *= 100;
        else if (milliseconds.length() == 2) ms *= 10;
        
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms);
    }
    
    private String convertSRTFormattingToWebVTT(String text) {
        // Convert SRT HTML-like formatting to WebVTT
        return text
                .replace("<i>", "<i>")
                .replace("</i>", "</i>")
                .replace("<b>", "<b>")
                .replace("</b>", "</b>")
                .replace("<u>", "<u>")
                .replace("</u>", "</u>")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
    
    private void parseASSStyle(String line, Map<String, String> styles) {
        // Parse ASS style line: Style: Name,Arial,Fontsize,20,PrimaryColour,&Hffffff,SecondaryColour,&H000000,OutlineColour,0
        String[] parts = line.substring(7).split(",");
        if (parts.length > 1) {
            styles.put(parts[0], line); // Store style by name
        }
    }
    
    private String convertASStyleToWebVTTCSS(String line, Map<String, String> styles) {
        String[] parts = line.substring(7).split(",");
        if (parts.length < 2) return "";
        
        String styleName = parts[0];
        StringBuilder css = new StringBuilder();
        css.append("::cue(").append(styleName).append(") {\n");
        
        // Extract key properties from ASS style
        for (int i = 1; i < parts.length; i++) {
            String[] keyValue = parts[i].split("=");
            if (keyValue.length == 2) {
                css.append(convertASSStylePropertyToCSS(keyValue[0].trim(), keyValue[1].trim()));
            }
        }
        
        css.append("}\n");
        return css.toString();
    }
    
    private String convertASSStylePropertyToCSS(String assProperty, String value) {
        // Map ASS properties to CSS properties
        switch (assProperty.toLowerCase()) {
            case "primarycolour":
                return "  color: " + convertASSToCSSColor(value) + ";\n";
            case "secondarycolour":
                return "  background-color: " + convertASSToCSSColor(value) + ";\n";
            case "outlinecolour":
                return "  text-shadow: 2px 2px " + convertASSToCSSColor(value) + ";\n";
            case "fontsize":
                return "  font-size: " + value + "px;\n";
            case "fontname":
                return "  font-family: '" + value + "';\n";
            case "bold":
                return "1".equals(value) ? "  font-weight: bold;\n" : "";
            case "italic":
                return "1".equals(value) ? "  font-style: italic;\n" : "";
            case "underline":
                return "1".equals(value) ? "  text-decoration: underline;\n" : "";
            default:
                return "  /* " + assProperty + "=" + value + " */\n";
        }
    }
    
    private String convertASSToCSSColor(String assColor) {
        // Convert ASS BGR color to CSS RGB
        if (assColor.startsWith("&H")) {
            try {
                long color = Long.parseLong(assColor.substring(2), 16);
                int b = (int)(color & 0xFF);
                int g = (int)((color >> 8) & 0xFF);
                int r = (int)((color >> 16) & 0xFF);
                return String.format("rgb(%d, %d, %d)", r, g, b);
            } catch (NumberFormatException e) {
                return assColor; // Fallback to original
            }
        }
        return assColor;
    }
    
    private String convertASSDialogueToWebVTT(String line, Map<String, String> styles) {
        // Parse ASS dialogue: Dialogue: Layer=0,Start=0:00:01.23,End=0:00:04.23,Style=Default,Name=Narrator,Text=Hello world
        String[] parts = line.substring(10).split(",");
        
        if (parts.length < 10) return "";
        
        String startTime = convertASSTimeToWebVTT(parts[2]); // Start time
        String endTime = convertASSTimeToWebVTT(parts[3]);     // End time
        
        // Extract text (last part)
        String text = parts[9];
        
        // Remove ASS formatting codes
        text = cleanASSText(text);
        
        return String.format("%s --> %s\n%s", startTime, endTime, text);
    }
    
    private String convertASSTimeToWebVTT(String assTime) {
        // Convert ASS time format H:MM:SS.cs to WebVTT H:MM:SS.mmm
        try {
            assTime = assTime.trim();
            String[] timeParts = assTime.split(":");
            if (timeParts.length != 3) return "00:00:00.000";
            
            int hours = Integer.parseInt(timeParts[0]);
            int minutes = Integer.parseInt(timeParts[1]);
            
            String[] secondsParts = timeParts[2].split("\\.");
            int seconds = Integer.parseInt(secondsParts[0]);
            
            // cs is centiseconds (1/100), WebVTT wants milliseconds (1/1000)
            int ms = 0;
            if (secondsParts.length > 1) {
                String csStr = secondsParts[1];
                if (csStr.length() == 1) ms = Integer.parseInt(csStr) * 100;
                else if (csStr.length() == 2) ms = Integer.parseInt(csStr) * 10;
                else if (csStr.length() >= 3) ms = Integer.parseInt(csStr.substring(0, 3));
            }
            
            return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, ms);
        } catch (Exception e) {
            return "00:00:00.000";
        }
    }
    
    private String cleanASSText(String text) {
        // Remove ASS formatting codes like {\pos(x,y)} {\b1} text {\b0}
        return text.replaceAll("\\{[^}]*\\}", "")
                  .replaceAll("\\\\N", "\n")
                  .replaceAll("&lt;", "<")
                  .replaceAll("&gt;", ">")
                  .replaceAll("&amp;", "&");
    }
    
    private boolean isNumericLine(String line) {
        return line.matches("^\\d+$");
    }
}