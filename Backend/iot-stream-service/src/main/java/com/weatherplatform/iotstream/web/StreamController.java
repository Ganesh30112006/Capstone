package com.weatherplatform.iotstream.web;

import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.ingest.TelemetrySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operational surface for the streaming branch, proxied at {@code /api/stream/**}.
 * Reports only status — it exposes no device controls.
 */
@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final TelemetrySource source;
    private final LiveSessionRegistry registry;
    private final IotStreamProperties properties;

    public StreamController(TelemetrySource source,
                            LiveSessionRegistry registry,
                            IotStreamProperties properties) {
        this.source = source;
        this.registry = registry;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "source", source.name(),
                "connected", source.isConnected(),
                "activeClients", registry.size(),
                "fanout", Map.of(
                        "publishToRabbit", properties.getFanout().isPublishToRabbit(),
                        "broadcastToWebSocket", properties.getFanout().isBroadcastToWebSocket(),
                        "forwardAiEvents", properties.getFanout().isForwardAiEvents()),
                "topicPrefix", properties.getTopicPrefix());
    }
}
