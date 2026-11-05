package com.stockguard.support;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The packaged application running as its own operating system process: its own JVM, heap, connection pool and
 * scheduler. Nothing can be shared with another instance except PostgreSQL, so any JVM-level coordination would
 * fail these tests. {@link #kill()} is a hard kill with no shutdown hooks and no chance to clean up.
 */
public final class AppInstance implements AutoCloseable {

    public static final String JWT_SECRET = "multi-instance-test-secret-multi-instance-test-secret";

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);

    private final String instanceId;
    private final int port;
    private final Map<String, String> environment;
    private Process process;

    private AppInstance(String instanceId, int port, Map<String, String> environment) {
        this.instanceId = instanceId;
        this.port = port;
        this.environment = environment;
    }

    /** Launches the process without waiting for it, so several instances can race through startup. */
    public static AppInstance launch(String instanceId, String jdbcUrl, String username, String password, Map<String, String> extraEnvironment) {
        Map<String, String> environment = new HashMap<>(Map.of(
                "STOCKGUARD_DB_URL", jdbcUrl,
                "STOCKGUARD_DB_USERNAME", username,
                "STOCKGUARD_DB_PASSWORD", password,
                "STOCKGUARD_JWT_SECRET", JWT_SECRET,
                "STOCKGUARD_INSTANCE_ID", instanceId,
                "STOCKGUARD_EXPIRY_SWEEP_INTERVAL", "500ms",
                "STOCKGUARD_ADMIN_USERNAME", "admin",
                "STOCKGUARD_ADMIN_PASSWORD", "integration-admin-password"));
        environment.putAll(extraEnvironment);
        AppInstance instance = new AppInstance(instanceId, freePort(), environment);
        instance.start();
        return instance;
    }

    public void start() {
        String jar = System.getProperty("stockguard.test.jar");
        if (jar == null || !new File(jar).isFile()) {
            throw new IllegalStateException("The packaged jar was not found (" + jar + "). Run these tests with: mvnw verify");
        }
        try {
            Path log = Path.of("target", "instances", instanceId + "-" + System.currentTimeMillis() + ".log");
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx256m",
                    "-XX:TieredStopAtLevel=1",
                    "-jar", jar,
                    "--server.port=" + port));
            builder.environment().putAll(environment);
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            process = builder.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start " + instanceId, ex);
        }
    }

    public AppInstance awaitHealthy() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest health = HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health"))
                .timeout(Duration.ofSeconds(2))
                .build();
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(instanceId + " exited during startup with code " + process.exitValue() + ", see target/instances");
            }
            try {
                if (client.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return this;
                }
            } catch (IOException ex) {
                // Not listening yet.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            sleep(200);
        }
        throw new IllegalStateException(instanceId + " did not become healthy in time");
    }

    /** Terminates the process immediately, like a crash or an out-of-memory kill. */
    public void kill() {
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Waits for the process to exit on its own and returns its exit code. */
    public int awaitExit(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(instanceId + " was expected to exit but is still running");
            }
            return process.exitValue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    public String id() {
        return instanceId;
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public Api api() {
        return new Api(baseUrl());
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            kill();
        }
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
