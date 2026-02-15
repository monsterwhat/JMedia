package Services;

import Controllers.SettingsController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Centralized logging service that provides the same addLog functionality
 * used by music scanning for video and metadata services.
 * This ensures consistent logging across all scanning operations.
 */
@ApplicationScoped
public class LoggingService {

    @Inject
    private SettingsController settingsController;

    /**
     * Logs a message using the same pattern as music scanning
     * (database storage + WebSocket broadcast)
     */
    public void addLog(String message) {
        settingsController.addLog(message);
    }

    /**
     * Logs a message with exception using the same pattern as music scanning
     * (database storage + WebSocket broadcast + stack trace)
     */
    public void addLog(String message, Throwable throwable) {
        settingsController.addLog(message, throwable);
    }

    /**
     * Logs multiple messages in batch (used for parallel processing results)
     */
    public void addLogs(java.util.List<String> messages) {
        settingsController.addLogs(messages);
    }
}