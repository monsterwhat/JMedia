package Utils;

import java.util.regex.Pattern;

public class VersionComparator {
    
    private static final Pattern SEMANTIC_VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Pattern ALPHA_VERSION_PATTERN = Pattern.compile("^Alpha-\\d+$");
    
    public static boolean isNewerVersion(String current, String latest) {
        if (current == null || latest == null) {
            return false;
        }
        
        // Handle semantic versions (0.9.0, 1.0.0, etc.)
        if (SEMANTIC_VERSION_PATTERN.matcher(current).matches() && 
            SEMANTIC_VERSION_PATTERN.matcher(latest).matches()) {
            return compareSemanticVersions(current, latest) < 0;
        }
        
        // Handle alpha versions (Alpha-8, Alpha-7, etc.)
        if (ALPHA_VERSION_PATTERN.matcher(current).matches() && 
            ALPHA_VERSION_PATTERN.matcher(latest).matches()) {
            return compareAlphaVersions(current, latest) < 0;
        }
        
        // Handle mixed comparison - semantic versions are always newer than alpha versions
        if (SEMANTIC_VERSION_PATTERN.matcher(latest).matches() && 
            ALPHA_VERSION_PATTERN.matcher(current).matches()) {
            return true;
        }
        
        // If latest is alpha and current is semantic, it's not newer
        if (ALPHA_VERSION_PATTERN.matcher(latest).matches() && 
            SEMANTIC_VERSION_PATTERN.matcher(current).matches()) {
            return false;
        }
        
        // Fallback to string comparison
        return current.compareTo(latest) < 0;
    }
    
    private static int compareSemanticVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        
        for (int i = 0; i < 3; i++) {
            int num1 = Integer.parseInt(parts1[i]);
            int num2 = Integer.parseInt(parts2[i]);
            
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        
        return 0;
    }
    
    private static int compareAlphaVersions(String v1, String v2) {
        int num1 = Integer.parseInt(v1.substring("Alpha-".length()));
        int num2 = Integer.parseInt(v2.substring("Alpha-".length()));
        return Integer.compare(num1, num2);
    }
    
    public static String normalizeVersion(String version) {
        if (version == null) {
            return "0.0.0";
        }
        
        // Convert alpha versions to semantic-like format for display
        if (ALPHA_VERSION_PATTERN.matcher(version).matches()) {
            int alphaNum = Integer.parseInt(version.substring("Alpha-".length()));
            return "0." + alphaNum + ".0";
        }
        
        // Return semantic versions as-is
        if (SEMANTIC_VERSION_PATTERN.matcher(version).matches()) {
            return version;
        }
        
        // Fallback
        return "0.0.0";
    }
}