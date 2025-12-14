package Detectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SubtitleMatcher {

    private static final List<String> SUBTITLE_EXTENSIONS = List.of(".srt", ".vtt", ".ass", ".ssa");
    
    // Pattern to match common language codes (en, eng) or descriptors (forced, sdh)
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-zA-Z]{2,3}$|^forced$|^sdh$");

    public static List<Path> findExternalSubtitlesForVideo(Path video) {
        Path videoDirectory = video.getParent();
        if (videoDirectory == null || !Files.isDirectory(videoDirectory)) {
            return List.of();
        }

        String videoBasename = getBasename(video.getFileName().toString());

        List<Path> exactMatches = new ArrayList<>();
        List<Path> languageMatches = new ArrayList<>();
        List<Path> looseMatches = new ArrayList<>();

        try (Stream<Path> files = Files.list(videoDirectory)) {
            files.filter(Files::isRegularFile)
                 .filter(f -> isSubtitleFile(f))
                 .forEach(subtitleFile -> {
                     String subtitleBasename = getBasename(subtitleFile.getFileName().toString());

                     if (subtitleBasename.equals(videoBasename)) {
                         exactMatches.add(subtitleFile);
                     } else if (subtitleBasename.startsWith(videoBasename + ".")) {
                         String tag = subtitleBasename.substring(videoBasename.length() + 1);
                         if (TAG_PATTERN.matcher(tag).matches()) {
                             languageMatches.add(subtitleFile);
                         } else {
                            // This could be something like "Movie.Title.Extended.Edition.srt"
                            looseMatches.add(subtitleFile);
                         }
                     } else if (subtitleBasename.contains(videoBasename)) {
                         looseMatches.add(subtitleFile);
                     }
                 });
        } catch (IOException e) {
            System.err.println("Error scanning for subtitles in directory: " + videoDirectory);
            e.printStackTrace();
            return List.of(); // Return empty list on error
        }
        
        languageMatches.sort(Comparator.comparing(p -> p.getFileName().toString().length()));

        List<Path> sortedSubtitles = new ArrayList<>();
        sortedSubtitles.addAll(exactMatches);
        sortedSubtitles.addAll(languageMatches);
        sortedSubtitles.addAll(looseMatches);
        
        return sortedSubtitles.stream().distinct().collect(Collectors.toList());
    }
    
    private static boolean isSubtitleFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        for (String ext : SUBTITLE_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String getBasename(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return filename.substring(0, lastDotIndex);
        }
        return filename;
    }
}

