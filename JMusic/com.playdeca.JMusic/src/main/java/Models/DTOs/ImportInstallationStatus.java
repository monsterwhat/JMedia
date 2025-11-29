package Models.DTOs;

public class ImportInstallationStatus {
    public boolean pythonInstalled;
    public boolean spotdlInstalled;
    public boolean ffmpegInstalled;
    public boolean whisperInstalled;
    public String pythonMessage;
    public String spotdlMessage;
    public String ffmpegMessage;
    public String whisperMessage;

    public ImportInstallationStatus(boolean pythonInstalled, boolean spotdlInstalled, boolean ffmpegInstalled, boolean whisperInstalled, String pythonMessage, String spotdlMessage, String ffmpegMessage, String whisperMessage) {
        this.pythonInstalled = pythonInstalled;
        this.spotdlInstalled = spotdlInstalled;
        this.ffmpegInstalled = ffmpegInstalled;
        this.whisperInstalled = whisperInstalled;
        this.pythonMessage = pythonMessage;
        this.spotdlMessage = spotdlMessage;
        this.ffmpegMessage = ffmpegMessage;
        this.whisperMessage = whisperMessage;
    }

    public boolean isAllInstalled() {
        return pythonInstalled && spotdlInstalled && ffmpegInstalled && whisperInstalled;
    }
}
