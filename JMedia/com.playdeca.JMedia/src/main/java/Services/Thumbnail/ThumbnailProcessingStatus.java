package Services.Thumbnail;

/**
 * Tracks the status of thumbnail processing queue and progress
 */
public class ThumbnailProcessingStatus {
    
    public int totalJobs;
    public int processedJobs;
    public int successfulApi;
    public int successfulLocal;
    public int failed;
    public boolean isProcessing;
    public String currentJob;
    public boolean isOnline;
    public long lastApiCallTime;
    public long estimatedTimeRemaining;
    
    public ThumbnailProcessingStatus() {
        this.totalJobs = 0;
        this.processedJobs = 0;
        this.successfulApi = 0;
        this.successfulLocal = 0;
        this.failed = 0;
        this.isProcessing = false;
        this.currentJob = "Idle";
        this.isOnline = true;
        this.lastApiCallTime = 0;
        this.estimatedTimeRemaining = 0;
    }
    
    public void reset() {
        this.totalJobs = 0;
        this.processedJobs = 0;
        this.successfulApi = 0;
        this.successfulLocal = 0;
        this.failed = 0;
        this.currentJob = "Idle";
        this.estimatedTimeRemaining = 0;
    }
    
    public void startProcessing(int totalJobs) {
        this.totalJobs = totalJobs;
        this.processedJobs = 0;
        this.successfulApi = 0;
        this.successfulLocal = 0;
        this.failed = 0;
        this.isProcessing = true;
        this.currentJob = "Starting processing...";
    }
    
    public void markApiSuccess() {
        this.processedJobs++;
        this.successfulApi++;
    }
    
    public void markLocalSuccess() {
        this.processedJobs++;
        this.successfulLocal++;
    }
    
    public void markFailure() {
        this.processedJobs++;
        this.failed++;
    }
    
    public void finishProcessing() {
        this.isProcessing = false;
        this.currentJob = "Completed";
    }
    
    public int getRemainingJobs() {
        return totalJobs - processedJobs;
    }
    
    public double getProgressPercentage() {
        if (totalJobs == 0) return 0.0;
        return (double) processedJobs / totalJobs * 100.0;
    }
    
    public String getProgressText() {
        if (!isProcessing) {
            return "No active processing";
        }
        return String.format("Processing %d/%d (%.1f%%) - API: %d, Local: %d, Failed: %d", 
                           processedJobs, totalJobs, getProgressPercentage(), 
                           successfulApi, successfulLocal, failed);
    }
    
    @Override
    public String toString() {
        return getProgressText();
    }
}