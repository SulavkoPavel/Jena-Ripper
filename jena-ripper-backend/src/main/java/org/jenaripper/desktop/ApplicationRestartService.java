package org.jenaripper.desktop;

import org.jenaripper.JenaRipperApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ApplicationRestartService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationRestartService.class);

    private final ConfigurableApplicationContext applicationContext;
    private final String[] sourceArguments;
    private final AtomicBoolean restartRequested = new AtomicBoolean();

    public ApplicationRestartService(ConfigurableApplicationContext applicationContext,
                                     ApplicationArguments applicationArguments) {
        this.applicationContext = applicationContext;
        this.sourceArguments = applicationArguments.getSourceArgs();
    }

    public boolean requestRestart() {
        if (!restartRequested.compareAndSet(false, true)) return false;
        Thread restartThread = new Thread(this::restart, "jena-ripper-restart");
        restartThread.setDaemon(false);
        restartThread.start();
        return true;
    }

    private void restart() {
        try {
            Thread.sleep(500);
            log.info("Restarting Jena Ripper to apply the selected connection profile");
            applicationContext.close();
            SpringApplication.run(JenaRipperApplication.class, sourceArguments);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Jena Ripper restart was interrupted");
        } catch (RuntimeException exception) {
            log.error("Jena Ripper could not restart after applying the connection profile", exception);
        }
    }
}
