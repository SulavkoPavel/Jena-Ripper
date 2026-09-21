package org.jenaripper.desktop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("desktop")
@Slf4j
public class DesktopBrowserLauncher {
    private static final AtomicBoolean OPENED = new AtomicBoolean();

    @EventListener(ApplicationReadyEvent.class)
    public void openAfterStartup(ApplicationReadyEvent event) {
        if (!OPENED.compareAndSet(false, true)) return;
        if (!(event.getApplicationContext() instanceof WebServerApplicationContext context)) return;
        URI uri = URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/");
        log.info("Jena Ripper запущен: {}", uri);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            } else {
                log.warn("Браузер по умолчанию недоступен. Откройте Jena Ripper вручную: {}", uri);
            }
        } catch (Exception exception) {
            log.warn("Не удалось открыть браузер по умолчанию. Откройте Jena Ripper вручную: {}", uri);
        }
    }
}
