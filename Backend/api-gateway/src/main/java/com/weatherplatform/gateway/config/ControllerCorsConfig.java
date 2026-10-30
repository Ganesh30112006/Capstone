package com.weatherplatform.gateway.config;

import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilter;

/**
 * CORS for the endpoints the gateway serves itself.
 *
 * `spring.cloud.gateway.globalcors` is applied by the route handler mapping
 * only, so a @RestController in the gateway (SystemHealthController) had no
 * CORS policy at all: the browser's preflight was refused with 403 and the
 * console's System page could not load from any origin. This applies the same
 * globalcors policy to those paths, so there is one origin list to maintain.
 */
@Configuration
public class ControllerCorsConfig {

    static final String CONTROLLER_PATHS = "/api/system/";

    @Bean
    public WebFilter controllerCorsFilter(GlobalCorsProperties globalCors) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration policy = globalCors.getCorsConfigurations().get("/**");
        if (policy != null) {
            source.registerCorsConfiguration(CONTROLLER_PATHS + "**", policy);
        }
        CorsWebFilter cors = new CorsWebFilter(source);

        // CorsWebFilter ends every preflight it sees, configured or not, so it
        // must never see one meant for a proxied route: those are answered by
        // globalcors further down the chain.
        return (exchange, chain) -> exchange.getRequest().getPath().value().startsWith(CONTROLLER_PATHS)
                ? cors.filter(exchange, chain)
                : chain.filter(exchange);
    }
}
