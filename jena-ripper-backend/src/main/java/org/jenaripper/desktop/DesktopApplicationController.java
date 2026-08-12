package org.jenaripper.desktop;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@Profile("desktop")
@RequestMapping("/api/application")
public class DesktopApplicationController {
    private static final Logger log = LoggerFactory.getLogger(DesktopApplicationController.class);

    private final ConfigurableApplicationContext applicationContext;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    public DesktopApplicationController(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @GetMapping("/runtime")
    public RuntimeResponse runtime() {
        return new RuntimeResponse("desktop", true);
    }

    @PostMapping("/shutdown")
    public DeferredResult<ResponseEntity<ShutdownResponse>> shutdown(HttpServletRequest request) {
        DeferredResult<ResponseEntity<ShutdownResponse>> result = new DeferredResult<>();
        if (!isLoopback(request.getRemoteAddr())
                || !"ui".equals(request.getHeader("X-Jena-Ripper-Shutdown"))
                || !isSameOrigin(request)) {
            result.setResult(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ShutdownResponse(false, "Shutdown доступен только с localhost.")));
            return result;
        }

        if (shutdownRequested.compareAndSet(false, true)) {
            log.info("Jena Ripper shutdown requested by local UI");
            result.onCompletion(this::closeApplicationContext);
        }
        result.setResult(ResponseEntity.ok(new ShutdownResponse(true, "shutdown accepted")));
        return result;
    }

    private void closeApplicationContext() {
        if (!shutdownStarted.compareAndSet(false, true)) return;
        Thread shutdownThread = new Thread(() -> {
            log.info("Closing application context");
            applicationContext.close();
            log.info("Jena Ripper stopped");
        }, "jena-ripper-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }

    private static boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return true;
        try {
            URI uri = URI.create(origin);
            int originPort = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            return isLoopback(uri.getHost()) && originPort == request.getServerPort();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public record RuntimeResponse(String mode, boolean shutdownAvailable) {}
    public record ShutdownResponse(boolean accepted, String message) {}
}
