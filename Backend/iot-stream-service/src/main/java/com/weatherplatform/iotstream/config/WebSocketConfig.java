package com.weatherplatform.iotstream.config;

import com.weatherplatform.iotstream.security.WebSocketAuthInterceptor;
import com.weatherplatform.iotstream.web.LiveTelemetryWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Exposes the single client endpoint, {@code /ws/live}.
 *
 * <p>Allowed origins are an explicit list — the browser reaches this endpoint
 * through the API Gateway, so the gateway's own origin(s) are what matter here.
 * A wildcard is never used.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveTelemetryWebSocketHandler handler;
    private final WebSocketAuthInterceptor authInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(LiveTelemetryWebSocketHandler handler,
                           WebSocketAuthInterceptor authInterceptor,
                           @Value("${iot.stream.allowed-origins:http://localhost:5173,http://localhost:4173}")
                           String allowedOrigins) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins.split("\\s*,\\s*");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/live")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
