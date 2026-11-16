package com.growercare.testsupport;

import org.testcontainers.kafka.KafkaContainer;

/**
 * One single-node Kafka (KRaft) per test JVM. The GraalVM native image starts in about a second and uses little
 * memory, which keeps laptop Docker VMs healthy. The broker version matches {@code local-dev/docker-compose.yml}.
 */
public final class KafkaTestContainer {

    public static final String IMAGE = "apache/kafka-native:4.3.1";

    private static final KafkaContainer CONTAINER = new KafkaContainer(IMAGE);

    private KafkaTestContainer() { }

    public static synchronized KafkaContainer started() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static String bootstrapServers() {
        return started().getBootstrapServers();
    }
}
