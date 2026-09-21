package org.jenaripper.desktop;

import org.jenaripper.JenaRipperApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ApplicationRestartService {
    private static final long RESTART_DELAY_MS = 500;

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
            Thread.sleep(RESTART_DELAY_MS);
            int serverPort = currentServerPort(applicationContext);
            String[] restartArguments = withServerPort(sourceArguments, serverPort);
            log.info("Перезапуск Jena Ripper для применения выбранного профиля подключения на порту {}", serverPort);
            applicationContext.close();
            SpringApplication.run(JenaRipperApplication.class, restartArguments);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Перезапуск Jena Ripper был прерван");
        } catch (RuntimeException exception) {
            log.error("Не удалось перезапустить Jena Ripper после применения профиля подключения", exception);
        }
    }

    static int currentServerPort(ApplicationContext context) {
        if (context instanceof WebServerApplicationContext webApplicationContext) {
            return webApplicationContext.getWebServer().getPort();
        }
        return 0;
    }

    static String[] withServerPort(String[] arguments, int serverPort) {
        if (serverPort <= 0) return arguments.clone();
        List<String> result = new ArrayList<>();
        boolean skipPortValue = false;
        for (String argument : arguments) {
            if (skipPortValue) {
                skipPortValue = false;
                continue;
            }
            if ("--server.port".equals(argument)) {
                skipPortValue = true;
                continue;
            }
            if (argument.startsWith("--server.port=")) continue;
            result.add(argument);
        }
        result.add("--server.port=" + serverPort);
        return result.toArray(String[]::new);
    }
}
