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
    
    // Installation progress tracking (0-100)
    public int pythonInstallProgress;
    public int spotdlInstallProgress;
    public int ffmpegInstallProgress;
    public int whisperInstallProgress;
    
    // Installation status tracking
    public boolean pythonInstalling;
    public boolean spotdlInstalling;
    public boolean ffmpegInstalling;
    public boolean whisperInstalling;

    public ImportInstallationStatus(boolean pythonInstalled, boolean spotdlInstalled, boolean ffmpegInstalled, boolean whisperInstalled, String pythonMessage, String spotdlMessage, String ffmpegMessage, String whisperMessage) {
        this.pythonInstalled = pythonInstalled;
        this.spotdlInstalled = spotdlInstalled;
        this.ffmpegInstalled = ffmpegInstalled;
        this.whisperInstalled = whisperInstalled;
        this.pythonMessage = pythonMessage;
        this.spotdlMessage = spotdlMessage;
        this.ffmpegMessage = ffmpegMessage;
        this.whisperMessage = whisperMessage;
        
        // Initialize progress and installation status
        this.pythonInstallProgress = pythonInstalled ? 100 : 0;
        this.spotdlInstallProgress = spotdlInstalled ? 100 : 0;
        this.ffmpegInstallProgress = ffmpegInstalled ? 100 : 0;
        this.whisperInstallProgress = whisperInstalled ? 100 : 0;
        
        this.pythonInstalling = false;
        this.spotdlInstalling = false;
        this.ffmpegInstalling = false;
        this.whisperInstalling = false;
    }

    public boolean isAllInstalled() {
        return pythonInstalled && spotdlInstalled && ffmpegInstalled && whisperInstalled;
    }
}
