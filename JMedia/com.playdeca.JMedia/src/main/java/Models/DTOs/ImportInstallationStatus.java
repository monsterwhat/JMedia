package Models.DTOs;

public class ImportInstallationStatus {
    public boolean chocoInstalled;
    public boolean pythonInstalled;
    public boolean spotdlInstalled;
    public boolean ffmpegInstalled;
    public boolean whisperInstalled;
    public String chocoMessage;
    public String pythonMessage;
    public String spotdlMessage;
    public String ffmpegMessage;
    public String whisperMessage;
    
    // Installation progress tracking (0-100)
    public int chocoInstallProgress;
    public int pythonInstallProgress;
    public int spotdlInstallProgress;
    public int ffmpegInstallProgress;
    public int whisperInstallProgress;
    
    // Installation status tracking
    public boolean chocoInstalling;
    public boolean pythonInstalling;
    public boolean spotdlInstalling;
    public boolean ffmpegInstalling;
    public boolean whisperInstalling;

    public ImportInstallationStatus(boolean chocoInstalled, boolean pythonInstalled, boolean spotdlInstalled, boolean ffmpegInstalled, boolean whisperInstalled, String chocoMessage, String pythonMessage, String spotdlMessage, String ffmpegMessage, String whisperMessage) {
        this.chocoInstalled = chocoInstalled;
        this.pythonInstalled = pythonInstalled;
        this.spotdlInstalled = spotdlInstalled;
        this.ffmpegInstalled = ffmpegInstalled;
        this.whisperInstalled = whisperInstalled;
        this.chocoMessage = chocoMessage;
        this.pythonMessage = pythonMessage;
        this.spotdlMessage = spotdlMessage;
        this.ffmpegMessage = ffmpegMessage;
        this.whisperMessage = whisperMessage;
        
        // Initialize progress and installation status
        this.chocoInstallProgress = chocoInstalled ? 100 : 0;
        this.pythonInstallProgress = pythonInstalled ? 100 : 0;
        this.spotdlInstallProgress = spotdlInstalled ? 100 : 0;
        this.ffmpegInstallProgress = ffmpegInstalled ? 100 : 0;
        this.whisperInstallProgress = whisperInstalled ? 100 : 0;
        
        this.chocoInstalling = false;
        this.pythonInstalling = false;
        this.spotdlInstalling = false;
        this.ffmpegInstalling = false;
        this.whisperInstalling = false;
    }

    public boolean isAllInstalled() {
        return chocoInstalled && pythonInstalled && spotdlInstalled && ffmpegInstalled && whisperInstalled;
    }
}
