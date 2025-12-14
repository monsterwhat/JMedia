package Controllers;
 
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import Services.UpdateService;
 
@ApplicationScoped
@Startup
public class DesktopController {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopController.class);

    @Inject
    SettingsController settings;

    @Inject
    SetupController setupController;

    @Inject
    UpdateService updateService;

    private final AtomicInteger activeClients = new AtomicInteger(0);
    private volatile boolean hasHadClient = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private TrayIcon trayIcon; // keep reference to ensure only one icon

    void onStart(@Observes StartupEvent ev) {
        LOG.info("\n" +
                  "JMedia - Free Software\n" +
                 "This program comes with ABSOLUTELY NO WARRANTY; for details see GPL-3.0.txt.\n" +
                 "This is free software, and you are welcome to redistribute it\n" +
                 "under certain conditions; see GPL-3.0.txt for details.");
        settings.addLog("Application starting...");
        // Skip tray icon in native builds to avoid AWT issues
        startTrayIcon();
        startBrowser();
        settings.addLog("Application started.");
        
        // Check for updates on startup
        checkForUpdatesOnStartup();
    }
    
    @PostConstruct
    public void checkForUpdatesOnStartup() {
        try {
            // Run update check asynchronously to avoid blocking startup
            scheduler.submit(() -> {
                try {
                    Thread.sleep(5000); // Wait 5 seconds after startup
                    updateService.checkForUpdatesAsync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            LOG.error("Error scheduling update check", e);
        }
    }
    
    private boolean isNativeBuild() {
        try {
            // Check if we're running in native mode
            Class.forName("org.graalvm.nativeimage.ImageInfo");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void startBrowser() {
        try {
            if (Desktop.isDesktopSupported()) {
                String url = setupController.isFirstTimeSetup() ? 
                    "http://localhost:80/setup.html" : 
                    "http://localhost:80";
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startTrayIcon() {
        if (!SystemTray.isSupported()) {
            System.out.println("System tray not supported!");
            return;
        }

        // Prevent adding multiple tray icons
        for (TrayIcon existingIcon : SystemTray.getSystemTray().getTrayIcons()) {
            if (existingIcon.getToolTip().equals("JMedia")) {
                SystemTray.getSystemTray().remove(existingIcon);
            }
        }

        PopupMenu menu = new PopupMenu();

        // Open browser button
        MenuItem openItem = new MenuItem("Open JMedia");
        openItem.addActionListener(e -> {
            String url = setupController.isFirstTimeSetup() ? 
                "http://localhost:80/setup.html" : 
                "http://localhost:80";
            openBrowser(url);
        });
        menu.add(openItem);

        // Exit button
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(exitItem);

        // Create tray icon
        Image iconImage = loadImage("META-INF/resources/logo.png");
        if (iconImage == null) {
            System.out.println("Failed to load tray icon image!");
        } else {
            trayIcon = new TrayIcon(iconImage, "JMedia", menu);
            trayIcon.setImageAutoSize(true);
            trayIcon.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        String url = setupController.isFirstTimeSetup() ? 
                            "http://localhost:80/setup.html" : 
                            "http://localhost:80";
                        openBrowser(url);
                    }
                }
            });
            try {
                SystemTray.getSystemTray().add(trayIcon);
            } catch (AWTException e) {
                e.printStackTrace();
            }
        }
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private Image loadImage(String resourceName) {
        try {
            return Toolkit.getDefaultToolkit().createImage(
                    getClass().getClassLoader().getResource(resourceName)
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void clientConnected() {
        settings.addLog("Client connected.");
        activeClients.incrementAndGet();
        hasHadClient = true;
    }

    public void clientDisconnected() {
        settings.addLog("Client disconnected.");
        int count = activeClients.decrementAndGet();
        if (count < 0) {
            activeClients.set(0);
        }

        if (!settings.getOrCreateSettings().getRunAsService()) { // Only shut down if not running as a service
            scheduler.schedule(() -> {
                if (activeClients.get() <= 0 && hasHadClient) {
                    Quarkus.asyncExit(); // graceful shutdown
                }
            }, 5, TimeUnit.SECONDS);
        }
    }

    public int getActiveClients() {
        return activeClients.get();
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdown();
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null; // Clear the static reference
        }
        settings.addLog("Application shutting down.");
    }

}
