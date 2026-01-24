package Models.DTOs;

public class ImportInstallationStatus {
    public boolean chocoInstalled;
    public boolean pythonInstalled;
    public boolean spotdlInstalled;
    public boolean ytdlpInstalled;
    public boolean ffmpegInstalled;
    public boolean whisperInstalled;
    public String chocoMessage;
    public String pythonMessage;
    public String spotdlMessage;
    public String ytdlpMessage;
    public String ffmpegMessage;
    public String whisperMessage;
    
    // Installation progress tracking (0-100)
    public int chocoInstallProgress;
    public int pythonInstallProgress;
    public int spotdlInstallProgress;
    public int ytdlpInstallProgress;
    public int ffmpegInstallProgress;
    public int whisperInstallProgress;
    
    // Installation status tracking
    public boolean chocoInstalling;
    public boolean pythonInstalling;
    public boolean spotdlInstalling;
    public boolean ytdlpInstalling;
    public boolean ffmpegInstalling;
    public boolean whisperInstalling;

    public ImportInstallationStatus(boolean chocoInstalled, boolean pythonInstalled, boolean spotdlInstalled, boolean ytdlpInstalled, boolean ffmpegInstalled, boolean whisperInstalled, String chocoMessage, String pythonMessage, String spotdlMessage, String ytdlpMessage, String ffmpegMessage, String whisperMessage) {
        this.chocoInstalled = chocoInstalled;
        this.pythonInstalled = pythonInstalled;
        this.spotdlInstalled = spotdlInstalled;
        this.ytdlpInstalled = ytdlpInstalled;
        this.ffmpegInstalled = ffmpegInstalled;
        this.whisperInstalled = whisperInstalled;
        this.chocoMessage = chocoMessage;
        this.pythonMessage = pythonMessage;
        this.spotdlMessage = spotdlMessage;
        this.ytdlpMessage = ytdlpMessage;
        this.ffmpegMessage = ffmpegMessage;
        this.whisperMessage = whisperMessage;
        
        // Initialize progress and installation status
        this.chocoInstallProgress = chocoInstalled ? 100 : 0;
        this.pythonInstallProgress = pythonInstalled ? 100 : 0;
        this.spotdlInstallProgress = spotdlInstalled ? 100 : 0;
        this.ytdlpInstallProgress = ytdlpInstalled ? 100 : 0;
        this.ffmpegInstallProgress = ffmpegInstalled ? 100 : 0;
        this.whisperInstallProgress = whisperInstalled ? 100 : 0;
        
        this.chocoInstalling = false;
        this.pythonInstalling = false;
        this.spotdlInstalling = false;
        this.ytdlpInstalling = false;
        this.ffmpegInstalling = false;
        this.whisperInstalling = false;
    }

    public boolean isAllInstalled() {
        return chocoInstalled && pythonInstalled && spotdlInstalled && ytdlpInstalled && ffmpegInstalled;
    }
}
