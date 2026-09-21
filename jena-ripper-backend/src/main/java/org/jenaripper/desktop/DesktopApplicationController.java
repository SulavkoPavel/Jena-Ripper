package org.jenaripper.desktop;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@Profile("desktop")
@RequestMapping(ApplicationApiPaths.BASE)
@RequiredArgsConstructor
@Slf4j
public class DesktopApplicationController {
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    private final ConfigurableApplicationContext applicationContext;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    @GetMapping("/runtime")
    public RuntimeResponse runtime() {
        return new RuntimeResponse("desktop", true, instanceId);
    }

    @PostMapping("/shutdown")
    public DeferredResult<ResponseEntity<ShutdownResponse>> shutdown(HttpServletRequest request) {
        DeferredResult<ResponseEntity<ShutdownResponse>> result = new DeferredResult<>();
        if (!isLoopback(request.getRemoteAddr())
                || !ApplicationApiPaths.UI_REQUEST_HEADER_VALUE.equals(
                        request.getHeader(ApplicationApiPaths.SHUTDOWN_REQUEST_HEADER))
                || !isSameOrigin(request)) {
            result.setResult(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ShutdownResponse(false, "Shutdown доступен только с localhost.")));
            return result;
        }

        if (shutdownRequested.compareAndSet(false, true)) {
            log.info("Локальный UI запросил завершение Jena Ripper");
            result.onCompletion(this::closeApplicationContext);
        }
        result.setResult(ResponseEntity.ok(new ShutdownResponse(true, "shutdown accepted")));
        return result;
    }

    private void closeApplicationContext() {
        if (!shutdownStarted.compareAndSet(false, true)) return;
        Thread shutdownThread = new Thread(() -> {
            log.info("Закрытие контекста приложения");
            applicationContext.close();
            log.info("Jena Ripper остановлен");
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
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) return true;
        try {
            URI uri = URI.create(origin);
            int originPort = uri.getPort() >= 0 ? uri.getPort()
                    : "https".equalsIgnoreCase(uri.getScheme()) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
            return isLoopback(uri.getHost()) && originPort == request.getServerPort();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public record RuntimeResponse(String mode, boolean shutdownAvailable, String instanceId) {}
    public record ShutdownResponse(boolean accepted, String message) {}
}
