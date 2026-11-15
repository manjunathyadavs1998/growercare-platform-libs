package com.growercare.testsupport;

import org.testcontainers.containers.GenericContainer;

/** One Redis 7.4 container per test JVM (password-protected like local-dev). */
public final class RedisTestContainer {

    public static final String PASSWORD = "test-redis-pw";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> CONTAINER = new GenericContainer<>("redis:7.4-alpine")
            .withCommand("redis-server", "--requirepass", PASSWORD, "--save", "", "--appendonly", "no")
            .withExposedPorts(6379);

    private RedisTestContainer() { }

    public static synchronized GenericContainer<?> started() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    public static String host() { return started().getHost(); }

    public static int port() { return started().getMappedPort(6379); }

    /** Simulates a Redis outage. */
    public static void pause() {
        started().getDockerClient().pauseContainerCmd(CONTAINER.getContainerId()).exec();
    }

    public static void unpause() {
        CONTAINER.getDockerClient().unpauseContainerCmd(CONTAINER.getContainerId()).exec();
    }
}
