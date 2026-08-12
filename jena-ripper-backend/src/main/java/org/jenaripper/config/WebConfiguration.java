package org.jenaripper.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final JenaRipperProperties properties;

    public WebConfiguration(JenaRipperProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.api().allowedOrigins().toArray(String[]::new);
        registry.addMapping("/api/**").allowedOriginPatterns(origins).allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
