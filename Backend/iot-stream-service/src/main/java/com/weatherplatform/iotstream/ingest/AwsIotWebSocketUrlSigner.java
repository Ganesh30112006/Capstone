package com.weatherplatform.iotstream.ingest;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.net.URI;

/**
 * Builds the SigV4 pre-signed {@code wss://} URL an MQTT-over-WebSocket client
 * uses to authenticate to AWS IoT Core with an IAM identity (no device
 * certificate).
 *
 * <p>The signing service name is {@code iotdevicegateway} and the canonical
 * path is {@code /mqtt}; the signature travels as {@code X-Amz-*} query
 * parameters, which is what lets a plain WebSocket handshake carry it.
 */
public class AwsIotWebSocketUrlSigner {

    private static final String SIGNING_NAME = "iotdevicegateway";
    private static final String CANONICAL_PATH = "/mqtt";

    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;

    public AwsIotWebSocketUrlSigner(AwsCredentialsProvider credentialsProvider, String region) {
        this.credentialsProvider = credentialsProvider;
        this.region = Region.of(region);
    }

    /**
     * @param endpoint the account's ATS data endpoint, host only
     *                 (e.g. {@code a1b2c3-ats.iot.us-east-1.amazonaws.com})
     * @return a fully signed {@code wss://host/mqtt?X-Amz-...} URI
     */
    public URI presignedUrl(String endpoint) {
        AwsCredentials credentials = credentialsProvider.resolveCredentials();

        SdkHttpFullRequest unsigned = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("wss")
                .host(endpoint)
                .port(443)
                .encodedPath(CANONICAL_PATH)
                .build();

        Aws4PresignerParams params = Aws4PresignerParams.builder()
                .awsCredentials(credentials)
                .signingName(SIGNING_NAME)
                .signingRegion(region)
                .build();

        SdkHttpFullRequest signed = Aws4Signer.create().presign(unsigned, params);
        return signed.getUri();
    }
}
