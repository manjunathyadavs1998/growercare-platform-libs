package com.growercare.testsupport;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * SPEC-CASE-05 D2: one SeaweedFS (S3 API) per test JVM, the same image as {@code local-dev}. An identities file is
 * mounted so every request must be signed (presigned URLs are really checked).
 */
public final class S3TestContainer {

    public static final String IMAGE = "chrislusf/seaweedfs:3.97";
    public static final String ACCESS_KEY = "gc-test-access";
    public static final String SECRET_KEY = "gc-test-secret";
    private static final int S3_PORT = 8333;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(S3TestContainer.class);

    @SuppressWarnings("resource")
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(IMAGE)
            .withCopyToContainer(MountableFile.forClasspathResource("seaweedfs/s3-test.json"), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-filer", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-s3.port=" + S3_PORT,
                    "-dir=/data", "-volume.max=100", "-master.volumeSizeLimitMB=64")   // one collection per bucket, several volumes each
            .withExposedPorts(S3_PORT)
            .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*", 1))
            .withLogConsumer(frame -> {                                     // SeaweedFS warnings/errors only (glog: W…/E…)
                var line = frame.getUtf8StringWithoutLineEnding();
                if (line.startsWith("E") || line.startsWith("W")) {
                    LOG.warn("seaweedfs: {}", line);
                }
            });

    private S3TestContainer() { }

    public static synchronized GenericContainer<?> started() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
            awaitWritable();
        }
        return CONTAINER;
    }

    /**
     * SeaweedFS opens the S3 port before its first volume is allocated; early writes fail with 500 InternalError.
     * Ready = a real object write succeeds (up to 60 s).
     */
    private static void awaitWritable() {
        try (var s3 = client()) {
            s3.createBucket(b -> b.bucket("gc-readiness"));
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (true) {
                try {
                    s3.putObject(b -> b.bucket("gc-readiness").key("probe"), software.amazon.awssdk.core.sync.RequestBody.fromString("ok"));
                    return;
                } catch (software.amazon.awssdk.core.exception.SdkException e) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("SeaweedFS did not accept writes within 60 s", e);
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
            }
        }
    }

    public static String endpoint() {
        return "http://" + started().getHost() + ":" + started().getMappedPort(S3_PORT);
    }

    /** A client with full rights, for test setup and assertions (writing odd objects, reading the clean bucket). */
    public static S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
    }
}
