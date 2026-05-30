package Models.DTOs;

public class ImportInstallationStatus {
    public boolean chocoInstalled;
    public boolean pythonInstalled;
    public boolean nodeInstalled;
    public boolean spotdlInstalled;
    public boolean ytdlpInstalled;
    public boolean ffmpegInstalled;
    public boolean parakeetInstalled;
    public String chocoMessage;
    public String pythonMessage;
    public String nodeMessage;
    public String spotdlMessage;
    public String ytdlpMessage;
    public String ffmpegMessage;
    public String parakeetMessage;
    
    // Installation progress tracking (0-100)
    public int chocoInstallProgress;
    public int pythonInstallProgress;
    public int nodeInstallProgress;
    public int spotdlInstallProgress;
    public int ytdlpInstallProgress;
    public int ffmpegInstallProgress;
    public int parakeetInstallProgress;
    
    // Installation status tracking
    public boolean chocoInstalling;
    public boolean pythonInstalling;
    public boolean nodeInstalling;
    public boolean spotdlInstalling;
    public boolean ytdlpInstalling;
    public boolean ffmpegInstalling;
    public boolean parakeetInstalling;

    public ImportInstallationStatus(boolean chocoInstalled, boolean pythonInstalled, boolean nodeInstalled, boolean spotdlInstalled, boolean ytdlpInstalled, boolean ffmpegInstalled, boolean parakeetInstalled, String chocoMessage, String pythonMessage, String nodeMessage, String spotdlMessage, String ytdlpMessage, String ffmpegMessage, String parakeetMessage) {
        this.chocoInstalled = chocoInstalled;
        this.pythonInstalled = pythonInstalled;
        this.spotdlInstalled = spotdlInstalled;
        this.ytdlpInstalled = ytdlpInstalled;
        this.ffmpegInstalled = ffmpegInstalled;
        this.parakeetInstalled = parakeetInstalled;
        this.chocoMessage = chocoMessage;
        this.pythonMessage = pythonMessage;
        this.nodeMessage = nodeMessage;
        this.spotdlMessage = spotdlMessage;
        this.ytdlpMessage = ytdlpMessage;
        this.ffmpegMessage = ffmpegMessage;
        this.parakeetMessage = parakeetMessage;
        
        // Initialize progress and installation status
        this.chocoInstallProgress = chocoInstalled ? 100 : 0;
        this.pythonInstallProgress = pythonInstalled ? 100 : 0;
        this.nodeInstallProgress = nodeInstalled ? 100 : 0;
        this.spotdlInstallProgress = spotdlInstalled ? 100 : 0;
        this.ytdlpInstallProgress = ytdlpInstalled ? 100 : 0;
        this.ffmpegInstallProgress = ffmpegInstalled ? 100 : 0;
        this.parakeetInstallProgress = parakeetInstalled ? 100 : 0;
        
        this.chocoInstalling = false;
        this.pythonInstalling = false;
        this.nodeInstalling = false;
        this.spotdlInstalling = false;
        this.ytdlpInstalling = false;
        this.ffmpegInstalling = false;
        this.parakeetInstalling = false;
    }

    public boolean isAllInstalled() {
        return chocoInstalled && pythonInstalled && spotdlInstalled && ytdlpInstalled && ffmpegInstalled;
    }
}
