package org.jenaripper.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationRestartServiceTest {
    @Test
    void readsPortFromRunningDesktopWebServer() {
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        WebServer webServer = mock(WebServer.class);
        when(context.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(49152);

        assertThat(ApplicationRestartService.currentServerPort(context)).isEqualTo(49152);
        assertThat(ApplicationRestartService.currentServerPort(mock(ConfigurableApplicationContext.class)))
                .isZero();
    }

    @Test
    void restartsDesktopApplicationOnItsCurrentPort() {
        String[] arguments = ApplicationRestartService.withServerPort(
                new String[]{"--spring.profiles.active=desktop", "--server.port=0"}, 49152);

        assertThat(arguments).containsExactly(
                "--spring.profiles.active=desktop", "--server.port=49152");
    }

    @Test
    void replacesSeparatedServerPortArgument() {
        String[] arguments = ApplicationRestartService.withServerPort(
                new String[]{"--server.port", "8082", "--sample=value"}, 49153);

        assertThat(arguments).containsExactly("--sample=value", "--server.port=49153");
    }

    @Test
    void preservesArgumentsWhenNoWebServerPortIsAvailable() {
        String[] source = new String[]{"--sample=value"};

        assertThat(ApplicationRestartService.withServerPort(source, 0))
                .containsExactly("--sample=value")
                .isNotSameAs(source);
    }
}
