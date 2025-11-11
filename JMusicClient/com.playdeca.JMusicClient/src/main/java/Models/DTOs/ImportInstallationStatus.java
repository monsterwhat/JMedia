package Models.DTOs;

public class ImportInstallationStatus {
    public boolean pythonInstalled;
    public boolean spotdlInstalled;
    public boolean ffmpegInstalled;
    public String pythonMessage;
    public String spotdlMessage;
    public String ffmpegMessage;

    public ImportInstallationStatus(boolean pythonInstalled, boolean spotdlInstalled, boolean ffmpegInstalled, String pythonMessage, String spotdlMessage, String ffmpegMessage) {
        this.pythonInstalled = pythonInstalled;
        this.spotdlInstalled = spotdlInstalled;
        this.ffmpegInstalled = ffmpegInstalled;
        this.pythonMessage = pythonMessage;
        this.spotdlMessage = spotdlMessage;
        this.ffmpegMessage = ffmpegMessage;
    }

    public boolean isAllInstalled() {
        return pythonInstalled && spotdlInstalled && ffmpegInstalled;
    }
}
