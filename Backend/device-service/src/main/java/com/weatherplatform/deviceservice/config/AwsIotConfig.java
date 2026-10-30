package com.weatherplatform.deviceservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * AWS clients for the control plane (Thing registry), the data plane
 * (publishing to devices) and S3 (firmware binaries).
 *
 * Credentials always come from {@link DefaultCredentialsProvider}, which
 * resolves the ECS task role in production. There is deliberately no way to
 * configure static keys here, so long-lived credentials cannot be baked into an
 * image or a config file.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "aws.iot.enabled", havingValue = "true")
public class AwsIotConfig {

    @Bean
    public IotClient iotClient(IotProperties properties,
                               @org.springframework.beans.factory.annotation.Value("${aws.region:us-east-1}") String region) {
        log.info("AWS IoT control plane enabled in {}", region);
        return IotClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public IotDataPlaneClient iotDataPlaneClient(
            IotProperties properties,
            @org.springframework.beans.factory.annotation.Value("${aws.region:us-east-1}") String region) {

        var builder = IotDataPlaneClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (properties.getMqttEndpoint() != null && !properties.getMqttEndpoint().isBlank()) {
            builder.endpointOverride(URI.create("https://" + properties.getMqttEndpoint()));
        }
        return builder.build();
    }

    @Bean
    public S3Client s3Client(@org.springframework.beans.factory.annotation.Value("${aws.region:us-east-1}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /** Used to hand devices a short-lived, signed firmware download URL. */
    @Bean
    public S3Presigner s3Presigner(@org.springframework.beans.factory.annotation.Value("${aws.region:us-east-1}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
