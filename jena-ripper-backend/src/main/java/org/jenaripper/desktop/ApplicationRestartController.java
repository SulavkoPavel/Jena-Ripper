package org.jenaripper.desktop;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;

@RestController
@RequestMapping(ApplicationApiPaths.BASE)
@RequiredArgsConstructor
public class ApplicationRestartController {
    private final ApplicationRestartService restartService;

    @PostMapping("/restart")
    public ResponseEntity<RestartResponse> restart(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())
                || !ApplicationApiPaths.UI_REQUEST_HEADER_VALUE.equals(
                        request.getHeader(ApplicationApiPaths.RESTART_REQUEST_HEADER))
                || !isLoopbackOrigin(request.getHeader(HttpHeaders.ORIGIN))) {
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
