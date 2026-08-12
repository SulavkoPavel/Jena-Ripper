package org.jenaripper.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DesktopApplicationControllerTest {
    @Test
    void exposesDesktopRuntime() {
        DesktopApplicationController controller = new DesktopApplicationController(mock(ConfigurableApplicationContext.class));

        assertThat(controller.runtime().mode()).isEqualTo("desktop");
        assertThat(controller.runtime().shutdownAvailable()).isTrue();
    }

    @Test
    void acceptsShutdownFromLoopback() {
        DesktopApplicationController controller = new DesktopApplicationController(mock(ConfigurableApplicationContext.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Jena-Ripper-Shutdown", "ui");

        ResponseEntity<DesktopApplicationController.ShutdownResponse> response = result(controller, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isTrue();
    }

    @Test
    void rejectsShutdownFromRemoteAddress() {
        DesktopApplicationController controller = new DesktopApplicationController(mock(ConfigurableApplicationContext.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Jena-Ripper-Shutdown", "ui");

        ResponseEntity<DesktopApplicationController.ShutdownResponse> response = result(controller, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isFalse();
    }

    @Test
    void rejectsShutdownFromAnotherBrowserOrigin() {
        DesktopApplicationController controller = new DesktopApplicationController(mock(ConfigurableApplicationContext.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerPort(8082);
        request.addHeader("X-Jena-Ripper-Shutdown", "ui");
        request.addHeader("Origin", "http://127.0.0.1:5173");

        ResponseEntity<DesktopApplicationController.ShutdownResponse> response = result(controller, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<DesktopApplicationController.ShutdownResponse> result(
            DesktopApplicationController controller, MockHttpServletRequest request) {
        return (ResponseEntity<DesktopApplicationController.ShutdownResponse>) controller.shutdown(request).getResult();
    }
}
