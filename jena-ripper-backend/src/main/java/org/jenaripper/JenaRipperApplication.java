package org.jenaripper;

import org.jenaripper.config.JenaRipperProperties;
import org.jenaripper.config.RdfSourceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JenaRipperProperties.class, RdfSourceProperties.class})
public class JenaRipperApplication {
    public static void main(String[] args) {
        SpringApplication.run(JenaRipperApplication.class, args);
    }
}
