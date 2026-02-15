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

@ApplicationScoped
public class SubtitleFormatConverter {
    
    private static final Pattern ASS_HEADER_PATTERN = Pattern.compile("^\\[V4\\+? Styles\\+?\\]");
    private static final Pattern ASS_DIALOGUE_PATTERN = Pattern.compile("^Dialogue:");
    private static final Pattern SRT_TIMESTAMP_PATTERN = 
        Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})");
    
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
    
    private String convertSRTToWebVTT(SubtitleTrack track) throws IOException {
        StringBuilder webVTT = new StringBuilder();
        webVTT.append("WEBVTT\n\n");
        
        String content = readSubtitleFile(track.fullPath);
        String[] lines = content.split("\\r?\\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                webVTT.append("\n");
                continue;
            }
            
            // Convert SRT timestamp to WebVTT format
            Matcher matcher = SRT_TIMESTAMP_PATTERN.matcher(line);
            if (matcher.find()) {
                String startTime = convertSRTTimeToWebVTT(
                    matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
                String endTime = convertSRTTimeToWebVTT(
                    matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8));
                
                webVTT.append(startTime)
                      .append(" --> ")
                      .append(endTime)
                      .append("\n");
                
                // Find the subtitle text (everything after the timestamp)
                int textStart = line.indexOf(',', matcher.end());
                String text = textStart > 0 ? line.substring(textStart + 1).trim() : "";
                
                // Convert SRT formatting to WebVTT
                text = convertSRTFormattingToWebVTT(text);
                webVTT.append(text).append("\n\n");
            } else if (isNumericLine(line)) {
                // Preserve line numbers as comments in WebVTT
                webVTT.append("NOTE ").append(line).append("\n\n");
            }
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
        // Try UTF-8 first, then other common encodings
        byte[] fileBytes = Files.readAllBytes(Path.of(fullPath));
        
        // Detect encoding by looking for BOM or common patterns
        Charset encoding = detectEncoding(fileBytes);
        return new String(fileBytes, encoding);
    }
    
    private Charset detectEncoding(byte[] fileBytes) {
        // Check for UTF-8 BOM
        if (fileBytes.length >= 3 && 
            (fileBytes[0] & 0xFF) == 0xEF && 
            (fileBytes[1] & 0xFF) == 0xBB && 
            (fileBytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        
        // Default to UTF-8 for most modern subtitle files
        return StandardCharsets.UTF_8;
    }
    
    private String convertSRTTimeToWebVTT(String hours, String minutes, String seconds, String milliseconds) {
        return String.format("%02d:%02d:%02d.%03d",
            Integer.parseInt(hours),
            Integer.parseInt(minutes),
            Integer.parseInt(seconds),
            Integer.parseInt(milliseconds));
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
        // Convert ASS time format H:MM:SS.cs to WebVTT
        try {
            String[] timeParts = assTime.split(":");
            if (timeParts.length != 3) return "";
            
            String hours = timeParts[0];
            String[] minutesParts = timeParts[1].split("\\.");
            String minutes = minutesParts[0];
            String[] secondsParts = minutesParts[1].split(",");
            String seconds = secondsParts[0];
            String centiseconds = secondsParts[1];
            
            return String.format("%02d:%02d:%02d.%03d",
                Integer.parseInt(hours),
                Integer.parseInt(minutes),
                Integer.parseInt(seconds),
                Integer.parseInt(centiseconds));
                
        } catch (Exception e) {
            return "";
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