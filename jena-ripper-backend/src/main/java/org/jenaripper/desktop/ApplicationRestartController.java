package org.jenaripper.desktop;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;

@RestController
@RequestMapping("/api/application")
public class ApplicationRestartController {
    private final ApplicationRestartService restartService;

    public ApplicationRestartController(ApplicationRestartService restartService) {
        this.restartService = restartService;
    }

    @PostMapping("/restart")
    public ResponseEntity<RestartResponse> restart(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())
                || !"ui".equals(request.getHeader("X-Jena-Ripper-Restart"))
                || !isLoopbackOrigin(request.getHeader("Origin"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new RestartResponse(false, "Restart доступен только с localhost."));
        }
        boolean accepted = restartService.requestRestart();
        return ResponseEntity.ok(new RestartResponse(accepted,
                accepted ? "restart accepted" : "restart already requested"));
    }

    private static boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLoopbackOrigin(String origin) {
        if (origin == null || origin.isBlank()) return true;
        try {
            return isLoopback(java.net.URI.create(origin).getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public record RestartResponse(boolean accepted, String message) {}
}
