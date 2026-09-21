package org.jenaripper.config;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfiguration implements WebMvcConfigurer {
    private final JenaRipperProperties properties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.api().allowedOrigins().toArray(String[]::new);
        registry.addMapping("/api/**").allowedOriginPatterns(origins)
                .allowedMethods(HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                        HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name());
    }
}
