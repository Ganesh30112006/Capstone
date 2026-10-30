package com.weatherplatform.deviceservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.dto.DeviceRegistrationRequest;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import com.weatherplatform.deviceservice.security.GatewayAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the request path a real client takes into this service:
 *
 * <pre>
 * HTTP -> GatewayAuthenticationFilter -> @PreAuthorize -> DeviceService
 *      -> JPA -> PostgreSQL (Flyway-migrated)
 * </pre>
 *
 * The authorization boundary is the point of most of these cases. Unit tests
 * call the service directly and so skip the filter chain entirely — they cannot
 * tell you whether a request that bypasses the gateway is refused, or whether a
 * USER-role token can write to the fleet. Those are properties of the wired
 * application, and this is where they get checked.
 *
 * The internal secret is set to a known value here so the direct-access check
 * is genuinely exercised; leaving it blank would disable it and make the
 * rejection tests pass for the wrong reason.
 *
 * Skipped rather than failed when no Docker daemon is available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "gateway.internal-secret=integration-test-secret",
        "gateway.enforce-origin=true"
})
@AutoConfigureMockMvc
class DeviceRegistryIT {

    private static final String GATEWAY_SECRET = "integration-test-secret";

    /** Stands in for every consumer of the fleet registry. */
    private static final String REGISTRATION_QUEUE = "test.device.registered";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void resetFleet() {
        deviceRepository.deleteAll();

        Queue queue = QueueBuilder.nonDurable(REGISTRATION_QUEUE).autoDelete().build();
        TopicExchange exchange = new TopicExchange(RabbitMQConfig.EXCHANGE_NAME, true, false);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange)
                .with(RabbitMQConfig.ROUTING_KEY_DEVICE_REGISTERED));
        amqpAdmin.purgeQueue(REGISTRATION_QUEUE, false);
    }

    @Test
    @DisplayName("an administrator can register a device and it persists to PostgreSQL")
    void registersADeviceThroughTheFullStack() throws Exception {
        DeviceRegistrationRequest request = registration("ESP32-IT-100", "North Ridge");

        mockMvc.perform(asAdmin(post("/api/devices"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceUid").value("ESP32-IT-100"));

        // Flyway's schema, the entity mapping and the defaults all have to agree
        // for this row to exist and read back.
        var stored = deviceRepository.findByDeviceUid("ESP32-IT-100");
        assertThat(stored).isPresent();
        assertThat(stored.get().getDeviceName()).isEqualTo("North Ridge");
        // A device is INACTIVE until it first reports; it is not assumed reachable.
        assertThat(stored.get().getStatus()).isEqualTo(DeviceStatus.INACTIVE);
        assertThat(stored.get().getRegisteredAt()).isNotNull();
    }

    @Test
    @DisplayName("registering a device announces it on the bus")
    void publishesTheRegistrationEvent() throws Exception {
        mockMvc.perform(asAdmin(post("/api/devices"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registration("ESP32-IT-103", "Cedar Valley"))))
                .andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Object event = rabbitTemplate.receiveAndConvert(REGISTRATION_QUEUE);
            assertThat(event)
                    .as("nothing was published to %s; no consumer would learn about the new station",
                            RabbitMQConfig.ROUTING_KEY_DEVICE_REGISTERED)
                    .isNotNull();
            assertThat(event.toString()).contains("ESP32-IT-103");
        });
    }

    @Test
    @DisplayName("registering the same device UID twice is refused with 409")
    void refusesDuplicateDeviceUid() throws Exception {
        String body = objectMapper.writeValueAsString(registration("ESP32-IT-101", "River Bend"));

        mockMvc.perform(asAdmin(post("/api/devices")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(post("/api/devices")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        assertThat(deviceRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a request that did not come through the gateway is refused")
    void refusesRequestsThatBypassTheGateway() throws Exception {
        // No X-Gateway-Auth header: this is what a request sent straight to the
        // container's published port looks like.
        mockMvc.perform(get("/api/devices")
                        .header(GatewayAuthenticationFilter.HEADER_USER_ID, "1")
                        .header(GatewayAuthenticationFilter.HEADER_USER_ROLE, "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a forged internal secret is refused")
    void refusesAForgedInternalSecret() throws Exception {
        mockMvc.perform(get("/api/devices")
                        .header(GatewayAuthenticationFilter.HEADER_INTERNAL_SECRET, "not-the-secret")
                        .header(GatewayAuthenticationFilter.HEADER_USER_ID, "1")
                        .header(GatewayAuthenticationFilter.HEADER_USER_ROLE, "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a USER may read the fleet but may not register a device")
    void enforcesTheAdminOnlyWriteBoundary() throws Exception {
        mockMvc.perform(asUser(get("/api/devices")))
                .andExpect(status().isOk());

        mockMvc.perform(asUser(post("/api/devices"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registration("ESP32-IT-102", "East Orchard"))))
                .andExpect(status().isForbidden());

        assertThat(deviceRepository.count())
                .as("a USER-role request must not create a device")
                .isZero();
    }

    @Test
    @DisplayName("a device command fails loudly when AWS IoT is not configured")
    void doesNotFakeSuccessWhenTheControlPathIsUnavailable() throws Exception {
        mockMvc.perform(asAdmin(post("/api/devices"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registration("ESP32-IT-104", "West Meadow"))))
                .andExpect(status().isCreated());

        // aws.iot.enabled is false here, as it is in local development and CI.
        // The operator must be told the command did not go anywhere, rather than
        // being shown an emulated success for a control path that does not exist.
        mockMvc.perform(asAdmin(post("/api/devices/ESP32-IT-104/commands"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"REBOOT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No command was sent")));
    }

    @Test
    @DisplayName("an unknown command name is refused before any dispatch code runs")
    void refusesACommandOutsideTheSupportedSet() throws Exception {
        mockMvc.perform(asAdmin(post("/api/devices"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registration("ESP32-IT-105", "South Gate"))))
                .andExpect(status().isCreated());

        // The enum is the guard: this fails JSON binding, so no arbitrary
        // instruction can reach the dispatch path.
        mockMvc.perform(asAdmin(post("/api/devices/ESP32-IT-105/commands"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"rm -rf /\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an invalid registration payload is rejected before it reaches the database")
    void rejectsAnInvalidPayload() throws Exception {
        DeviceRegistrationRequest invalid = new DeviceRegistrationRequest();
        invalid.setDeviceUid("x");          // shorter than the 3-character minimum
        invalid.setDeviceName("North Ridge");
        invalid.setLocationName("North Ridge");

        mockMvc.perform(asAdmin(post("/api/devices"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());

        assertThat(deviceRepository.count()).isZero();
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return withIdentity(builder, "ADMIN");
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder) {
        return withIdentity(builder, "USER");
    }

    /** Reproduces exactly what the gateway attaches to a verified request. */
    private MockHttpServletRequestBuilder withIdentity(MockHttpServletRequestBuilder builder, String role) {
        return builder
                .header(GatewayAuthenticationFilter.HEADER_INTERNAL_SECRET, GATEWAY_SECRET)
                .header(GatewayAuthenticationFilter.HEADER_USER_ID, "1")
                .header(GatewayAuthenticationFilter.HEADER_USER_EMAIL, "operator@example.com")
                .header(GatewayAuthenticationFilter.HEADER_USER_ROLE, role);
    }

    private DeviceRegistrationRequest registration(String deviceUid, String name) {
        DeviceRegistrationRequest request = new DeviceRegistrationRequest();
        request.setDeviceUid(deviceUid);
        request.setDeviceName(name);
        request.setLocationName(name + " · 4,180 ft");
        request.setLatitude(17.385);
        request.setLongitude(78.4867);
        request.setFirmwareVersion("v2.8.4");
        return request;
    }
}
