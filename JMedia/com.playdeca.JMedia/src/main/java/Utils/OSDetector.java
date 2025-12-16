package Utils;

public class OSDetector {
    
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    
    public static OSType detectOS() {
        if (OS_NAME.contains("win")) {
            return OSType.WINDOWS;
        } else if (OS_NAME.contains("nix") || OS_NAME.contains("nux") || OS_NAME.contains("aix")) {
            return OSType.LINUX;
        } else if (OS_NAME.contains("mac")) {
            return OSType.MACOS;
        } else {
            return OSType.UNKNOWN;
        }
    }
    
    public static boolean isWindows() {
        return detectOS() == OSType.WINDOWS;
    }
    
    public static boolean isLinux() {
        return detectOS() == OSType.LINUX;
    }
    
    public static boolean isMacOS() {
        return detectOS() == OSType.MACOS;
    }
    
    public static String getOSName() {
        return OS_NAME;
    }
}