package space.br1440.platform.leaderelection.testutil.extension.container;

import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;

public class EmbeddedRedisServer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedRedisServer.class);

    private static final String KEYDB_IMAGE = "eqalpha/keydb:alpine_x86_64_v6.3.4";
    private static final int KEYDB_PORT = 6379;
    private static final String READY_LOG_PATTERN = ".*(Ready to accept connections|Server initialized|ready to accept connections).*\\n";

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration PORT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FUNCTIONAL_TIMEOUT = Duration.ofSeconds(15);

    private static final long MEMORY_LIMIT = 128L * 1024 * 1024; // 128MB
    private static final long MEMORY_SWAP = 2L * MEMORY_LIMIT; // 256MB

    private final GenericContainer<?> redisContainer;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EmbeddedRedisServer(int port) {
        this.redisContainer = createContainer(port);
    }

    private GenericContainer<?> createContainer(int port) {
        //noinspection resource
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(KEYDB_IMAGE))
                .withExposedPorts(KEYDB_PORT)
                .withCommand(
                        "keydb-server",
                        "--server-threads", "2",
                        "--appendonly", "yes",
                        "--save", "",
                        "--maxmemory", "100mb",
                        "--maxmemory-policy", "allkeys-lru",
                        "--tcp-keepalive", "60"
                )
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withMemory(MEMORY_LIMIT)
                        .withMemorySwap(MEMORY_SWAP)
                        .withCpuQuota(200_000L)
                        .withCpuPeriod(100_000L)
                )
                .waitingFor(createEnterpriseWaitStrategy())
                .withLabels(Collections.singletonMap("component", "keydb-test"))
                .withReuse(true);

        if (port > 0) {
            container.setPortBindings(Collections.singletonList(port + ":" + KEYDB_PORT));
        }

        return container;
    }

    private WaitStrategy createEnterpriseWaitStrategy() {
        return new WaitAllStrategy()
                .withStrategy(Wait.forLogMessage(READY_LOG_PATTERN, 1).withStartupTimeout(STARTUP_TIMEOUT))
                .withStrategy(Wait.forListeningPort().withStartupTimeout(PORT_TIMEOUT))
                .withStrategy(new KeyDBFunctionalReadinessCheck())
                .withStartupTimeout(STARTUP_TIMEOUT);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("KeyDB server is already running");
        }

        try {
            redisContainer.start();
            log.info("Embedded KeyDB started successfully on {}:{}", getHost(), getPort());
        } catch (Exception e) {
            running.set(false);
            throw new IllegalStateException("Failed to start KeyDB", e);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                redisContainer.stop();
                log.info("KeyDB container stopped");
            } catch (Exception e) {
                log.warn("Error stopping KeyDB container", e);
            }
        }
    }

    public String getHost() {
        ensureRunning();
        return redisContainer.getHost();
    }

    public int getPort() {
        ensureRunning();
        return redisContainer.getMappedPort(KEYDB_PORT);
    }

    public void ensureRunning() {
        if (!running.get() || !redisContainer.isRunning()) {
            throw new IllegalStateException("KeyDB server is not running");
        }
    }

    public boolean isRunning() {
        return running.get() || redisContainer.isRunning();
    }

    public StringRedisTemplate createStringRedisTemplate() {
        ensureRunning();

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(getHost(), getPort());
        connectionFactory.afterPropertiesSet();
        return new StringRedisTemplate(connectionFactory);
    }

    public void clearRedis(StringRedisTemplate redisTemplate) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate cannot be null");
        }

        ensureRunning();
        
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match("*").build())) {
            Set<String> keys = new HashSet<>();
            cursor.forEachRemaining(keys::add);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    public boolean isRedisHealthy(StringRedisTemplate redisTemplate) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate cannot be null");
        }
        
        ensureRunning();
        
        try {
            String testKey = "health-check-" + System.nanoTime();
            String testValue = "test-value";
            
            redisTemplate.opsForValue().set(testKey, testValue);
            String retrievedValue = redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);
            
            return testValue.equals(retrievedValue);
        } catch (Exception e) {
            return false;
        }
    }

    private static class KeyDBFunctionalReadinessCheck extends AbstractWaitStrategy {

        private static final Logger log = LoggerFactory.getLogger(KeyDBFunctionalReadinessCheck.class);

        @Override
        protected void waitUntilReady() {
            String host = waitStrategyTarget.getHost();
            Integer mappedPort = waitStrategyTarget.getMappedPort(KEYDB_PORT);

            if (mappedPort == null) {
                throw new ContainerLaunchException("KeyDB port is not mapped");
            }

            try {
                await("KeyDB functional readiness")
                        .atMost(FUNCTIONAL_TIMEOUT)
                        .pollInterval(Duration.ofMillis(100))
                        .pollDelay(Duration.ZERO)
                        .ignoreException(JedisConnectionException.class)
                        .until(() -> isKeyDBFunctionallyReady(host, mappedPort));

                log.debug("KeyDB functional readiness check passed");
            } catch (ConditionTimeoutException e) {
                String message = String.format(
                        "KeyDB functional readiness check timed out after %d seconds. Host: %s, Port: %d",
                        FUNCTIONAL_TIMEOUT.getSeconds(), host, mappedPort
                );
                throw new ContainerLaunchException(message, e);
            } catch (Exception e) {
                throw new ContainerLaunchException("KeyDB functional readiness check failed unexpectedly", e);
            }
        }

        private boolean isKeyDBFunctionallyReady(String host, int port) {
            try (Jedis jedis = new Jedis(host, port, 2000, 2000)) {
                if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                    log.trace("KeyDB PING returned unexpected response");
                    return false;
                }

                String testKey = "_tc_readiness_" + System.nanoTime();
                jedis.set(testKey, "OK");
                String value = jedis.get(testKey);
                jedis.del(testKey);

                boolean ready = "OK".equals(value);
                if (!ready) {
                    log.trace("KeyDB SET/GET/DEL operations failed");
                }

                return ready;
            } catch (JedisConnectionException e) {
                log.trace("KeyDB connection failed: {}", e.getMessage());
                return false;
            }
        }
    }

}
