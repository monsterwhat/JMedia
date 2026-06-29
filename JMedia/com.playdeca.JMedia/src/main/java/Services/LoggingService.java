package Services;

import Controllers.SettingsController;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class LoggingService {

    @Inject
    private SettingsController settingsController;

    private String format(String message) {
        String caller = getCaller();
        String prefix = caller != null ? "[" + caller + "] " : "";
        return prefix + message;
    }

    private String getCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String selfName = getClass().getName();
        for (StackTraceElement e : stack) {
            String cn = e.getClassName();
            if (cn.equals(selfName) || cn.startsWith(selfName + "$") || cn.startsWith(selfName + "_")) {
                continue;
            }
            if (cn.startsWith("java.") || cn.startsWith("jakarta.") || cn.startsWith("jdk.")
                    || cn.startsWith("sun.") || cn.startsWith("io.quarkus.") || cn.startsWith("org.jboss.")
                    || cn.startsWith("io.netty.") || cn.startsWith("org.hibernate.")) {
                continue;
            }
            return cn.substring(cn.lastIndexOf('.') + 1) + "." + e.getMethodName();
        }
        return null;
    }

    public void addLog(String message) {
        String formatted = format(message);
        System.out.println(formatted);
        settingsController.addLog(formatted);
    }

    public void addLog(String message, Throwable throwable) {
        String formatted = format(message);
        System.err.println(formatted);
        if (throwable != null) {
            throwable.printStackTrace(System.err);
        }
        settingsController.addLog(formatted, throwable);
    }

    public void addLogs(java.util.List<String> messages) {
        for (String msg : messages) {
            addLog(msg);
        }
    }
}