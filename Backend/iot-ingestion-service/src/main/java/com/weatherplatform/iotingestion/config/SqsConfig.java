package com.weatherplatform.iotingestion.config;

import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;
import java.time.Duration;

/**
 * SQS client and listener wiring.
 *
 * Credentials follow the environment rather than configuration: on ECS/Fargate
 * the task's IAM role is used via {@link DefaultCredentialsProvider}, and
 * static keys are only ever used when an endpoint override points at a local
 * emulator. That makes it structurally impossible to ship real long-lived
 * access keys in a deployed image.
 */
@Slf4j
@Configuration
public class SqsConfig {

    @Value("${spring.cloud.aws.region.static:us-east-1}")
    private String region;

    /** Set only for LocalStack in local development; empty against real AWS. */
    @Value("${spring.cloud.aws.sqs.endpoint:}")
    private String endpointOverride;

    @Value("${spring.cloud.aws.credentials.access-key:}")
    private String accessKey;

    @Value("${spring.cloud.aws.credentials.secret-key:}")
    private String secretKey;

    @Value("${iot.sqs.max-concurrent-messages:10}")
    private int maxConcurrentMessages;

    @Value("${iot.sqs.poll-timeout-seconds:10}")
    private int pollTimeoutSeconds;

    @Bean
    public SqsAsyncClient sqsAsyncClient() {
        var builder = SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider());

        if (usingLocalEmulator()) {
            log.warn("SQS endpoint overridden to {} — local emulator mode. "
                    + "Never set AWS_SQS_ENDPOINT in a deployed environment.", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride));
        }

        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider() {
        if (usingLocalEmulator() && !accessKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        // ECS task role / instance profile / SSO — no secrets in config.
        return DefaultCredentialsProvider.create();
    }

    private boolean usingLocalEmulator() {
        return endpointOverride != null && !endpointOverride.isBlank();
    }

    @Bean
    public SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory(SqsAsyncClient sqsAsyncClient) {
        return SqsMessageListenerContainerFactory
                .builder()
                .sqsAsyncClient(sqsAsyncClient)
                .configure(options -> options
                        // ON_SUCCESS: the message is deleted only if the listener
                        // returned normally. A thrown exception leaves it for
                        // redelivery and, once maxReceiveCount is spent, the DLQ.
                        .acknowledgementMode(AcknowledgementMode.ON_SUCCESS)
                        .maxConcurrentMessages(maxConcurrentMessages)
                        .pollTimeout(Duration.ofSeconds(pollTimeoutSeconds)))
                .build();
    }
}
