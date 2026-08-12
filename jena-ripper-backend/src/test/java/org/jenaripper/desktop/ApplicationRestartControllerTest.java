package org.jenaripper.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationRestartControllerTest {
    private final ApplicationRestartService service = mock(ApplicationRestartService.class);
    private final ApplicationRestartController controller = new ApplicationRestartController(service);

    @Test
    void acceptsRestartFromLocalFrontend() {
        when(service.requestRestart()).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1", "http://localhost:5173");

        ResponseEntity<ApplicationRestartController.RestartResponse> response = controller.restart(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isTrue();
        verify(service).requestRestart();
    }

    @Test
    void rejectsRestartFromRemoteAddress() {
        MockHttpServletRequest request = request("192.168.1.15", "http://localhost:5173");

        ResponseEntity<ApplicationRestartController.RestartResponse> response = controller.restart(request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).requestRestart();
    }

    private static MockHttpServletRequest request(String remoteAddress, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("Origin", origin);
        request.addHeader("X-Jena-Ripper-Restart", "ui");
        return request;
    }
}
