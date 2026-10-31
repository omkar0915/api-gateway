package com.omkar.api_gateway.support;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import redis.embedded.RedisServer;

public final class RedisFixture implements AutoCloseable {

	private final GenericContainer<?> container;

	private final RedisServer embedded;

	private final String host;

	private final int port;

	private final String description;

	private RedisFixture(GenericContainer<?> container, RedisServer embedded, String host, int port, String description) {
		this.container = container;
		this.embedded = embedded;
		this.host = host;
		this.port = port;
		this.description = description;
	}

	public static RedisFixture start() {
		if (dockerAvailable()) {
			GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
					.withExposedPorts(6379);
			container.start();
			return new RedisFixture(container, null, container.getHost(), container.getMappedPort(6379),
					"Testcontainers redis:7-alpine");
		}
		int port = freePort();
		try {
			RedisServer embedded = RedisServer.newRedisServer()
					.port(port)
					.setting("bind 127.0.0.1")
					.setting("daemonize no")
					.build();
			embedded.start();
			return new RedisFixture(null, embedded, "127.0.0.1", port,
					"embedded Redis on 127.0.0.1:" + port + " because Docker is not available");
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not start embedded Redis for rate-limit tests", exception);
		}
	}

	public String host() {
		return host;
	}

	public int port() {
		return port;
	}

	public String description() {
		return description;
	}

	@Override
	public void close() throws IOException {
		if (container != null) {
			container.stop();
		}
		if (embedded != null) {
			embedded.stop();
		}
	}

	private static boolean dockerAvailable() {
		if (!dockerCommandPresent()) {
			return false;
		}
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		}
		catch (RuntimeException exception) {
			return false;
		}
	}

	private static boolean dockerCommandPresent() {
		String path = System.getenv("PATH");
		if (path == null || path.isBlank()) {
			return false;
		}
		for (String entry : path.split(File.pathSeparator)) {
			if (new File(entry, "docker").isFile() || new File(entry, "docker.exe").isFile()) {
				return true;
			}
		}
		return false;
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Could not reserve a local port for Redis", exception);
		}
	}

}
