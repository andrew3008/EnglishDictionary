package space.br1440.platform.leaderelection.testutil.extension;

import org.junit.jupiter.api.extension.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.aspect.DistributedLockAspect;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.leaderelection.LeaderElector;
import space.br1440.platform.leaderelection.leaderelection.LeaderElectorInitiator;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.SchedulerLockAdvisor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithEmbeddedRedis;
import space.br1440.platform.leaderelection.testutil.extension.annotation.WithLeaderElectionMocks;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;
import space.br1440.platform.leaderelection.testutil.mock.LeaderElectionMockContext;
import space.br1440.platform.leaderelection.testutil.support.RedisTestSupport;

import java.net.ServerSocket;
import java.util.Optional;

public class LeaderElectionTestExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(LeaderElectionTestExtension.class);

    private static final String REDIS_SERVER_KEY = "embeddedRedisServer";
    private static final String CONNECTION_FACTORY_KEY = "redisConnectionFactory";
    private static final String TEST_SUPPORT_KEY = "redisTestSupport";
    private static final String MOCK_CONTEXT_KEY = "mockContext";

    @Override
    public void beforeAll(ExtensionContext context) {
        if (isLeaderElectionTestDisabled(context)) {
            return;
        }

        if (hasEmbeddedRedisAnnotation(context)) {
            EmbeddedRedisServer redisServer = getStore(context).get(REDIS_SERVER_KEY, EmbeddedRedisServer.class);
            if (redisServer == null) {
                redisServer = startEmbeddedRedis(context);
                getStore(context).put(REDIS_SERVER_KEY, redisServer);

                RedisConnectionFactory connectionFactory = createConnectionFactory(redisServer);
                getStore(context).put(CONNECTION_FACTORY_KEY, connectionFactory);

                RedisTestSupport testSupport = new RedisTestSupport(connectionFactory);
                getStore(context).put(TEST_SUPPORT_KEY, testSupport);

                if (shouldCreateMocks(context)) {
                    LeaderElectionMockContext mockContext = createMockContext(connectionFactory);
                    getStore(context).put(MOCK_CONTEXT_KEY, mockContext);
                }
            }
        } else if (shouldCreateMocks(context)) {
            LeaderElectionMockContext mockContext = getStore(context).get(MOCK_CONTEXT_KEY, LeaderElectionMockContext.class);
            if (mockContext == null) {
                mockContext = createMockContext(null);
                getStore(context).put(MOCK_CONTEXT_KEY, mockContext);
            }
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (isLeaderElectionTestDisabled(context)) {
            return;
        }

        if (shouldCleanupBeforeEach(context)) {
            RedisTestSupport testSupport = getTestSupport(context);
            if (testSupport != null) {
                testSupport.flushAll();
            }
        }

        LeaderElectionMockContext mockContext = getMockContext(context);
        if (mockContext != null) {
            mockContext.resetAllMocks();
            mockContext.configureDefaultBehaviors();
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (isLeaderElectionTestDisabled(context)) {
            return;
        }

        RedisTestSupport testSupport = getTestSupport(context);
        if (testSupport != null) {
            testSupport.flushAll();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (isLeaderElectionTestDisabled(context)) {
            return;
        }

        if (context.getParent().isPresent() && context.getParent().get().getTestClass().isPresent()) {
            return;
        }

        LeaderElectionMockContext mockContext = getMockContext(context);
        if (mockContext != null) {
            mockContext.close();
        }

        RedisConnectionFactory connectionFactory = getStore(context).remove(CONNECTION_FACTORY_KEY, RedisConnectionFactory.class);
        if (connectionFactory != null) {
            try {
                connectionFactory.getConnection().close();
            } catch (Exception ignored) {
            }
        }

        EmbeddedRedisServer redisServer = getStore(context).remove(REDIS_SERVER_KEY, EmbeddedRedisServer.class);
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        if (isLeaderElectionTestDisabled(extensionContext)) {
            return false;
        }

        Class<?> type = parameterContext.getParameter().getType();
        return type == RedisConnectionFactory.class ||
                type == RedisTestSupport.class ||
                type == EmbeddedRedisServer.class ||
                type == LeaderElectionMockContext.class ||
                type == DistributedLock.class ||
                type == DistributedLockRegistry.class ||
                type == LeaderElector.class ||
                type == LeadershipEventPublisher.class ||
                type == LeaderElectorInitiator.class ||
                type == LockConfigurationExtractor.class ||
                type == LockHookProcessor.class ||
                type == SchedulerLockAdvisor.class ||
                type == DistributedLockAspect.class ||
                type == LeaderElectionMetricsInterface.class ||
                type == LeaderElectionProperties.class ||
                type == Environment.class ||
                type == StringRedisTemplate.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();

        if (type == RedisConnectionFactory.class) {
            return getConnectionFactory(extensionContext);
        }

        if (type == RedisTestSupport.class) {
            return getTestSupport(extensionContext);
        }

        if (type == EmbeddedRedisServer.class) {
            return getRedisServer(extensionContext);
        }

        if (type == LeaderElectionMockContext.class) {
            return getMockContext(extensionContext);
        }

        LeaderElectionMockContext mockContext = getMockContext(extensionContext);
        if (mockContext != null) {
            return mockContext.getBean(type);
        }

        throw new IllegalStateException("Unsupported parameter type: " + type);
    }

    private boolean isLeaderElectionTestDisabled(ExtensionContext context) {
        return !hasEmbeddedRedisAnnotation(context) && !hasLeaderElectionMocksAnnotation(context);
    }

    private boolean hasEmbeddedRedisAnnotation(ExtensionContext context) {
        return findEmbeddedRedisAnnotation(context).isPresent();
    }

    private boolean hasLeaderElectionMocksAnnotation(ExtensionContext context) {
        return findLeaderElectionMocksAnnotation(context).isPresent();
    }

    private Optional<Class<?>> findEmbeddedRedisAnnotation(ExtensionContext context) {
        ExtensionContext current = context;
        while (current != null) {
            Optional<Class<?>> testClass = current.getTestClass();
            if (testClass.isPresent() && testClass.get().isAnnotationPresent(WithEmbeddedRedis.class)) {
                return testClass;
            }
            current = current.getParent().orElse(null);
        }
        return Optional.empty();
    }

    private Optional<Class<?>> findLeaderElectionMocksAnnotation(ExtensionContext context) {
        ExtensionContext current = context;
        while (current != null) {
            Optional<Class<?>> testClass = current.getTestClass();
            if (testClass.isPresent() && testClass.get().isAnnotationPresent(WithLeaderElectionMocks.class)) {
                return testClass;
            }
            current = current.getParent().orElse(null);
        }
        return Optional.empty();
    }

    private boolean shouldCreateMocks(ExtensionContext context) {
        return hasLeaderElectionMocksAnnotation(context);
    }

    private boolean shouldCleanupBeforeEach(ExtensionContext context) {
        return findEmbeddedRedisAnnotation(context)
                .map(c -> c.getAnnotation(WithEmbeddedRedis.class).cleanupBeforeEach())
                .orElse(false);
    }

    private EmbeddedRedisServer startEmbeddedRedis(ExtensionContext context) {
        WithEmbeddedRedis annotation = findEmbeddedRedisAnnotation(context)
                .map(c -> c.getAnnotation(WithEmbeddedRedis.class))
                .orElseThrow(() -> new IllegalStateException("@WithEmbeddedRedis not found"));

        int port = annotation.redisPort();
        if (port == 0) {
            port = findAvailablePort();
        }

        EmbeddedRedisServer server = new EmbeddedRedisServer(port);
        server.start();
        return server;
    }

    private int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Could not find available port", e);
        }
    }

    private RedisConnectionFactory createConnectionFactory(EmbeddedRedisServer server) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", server.getPort());
        factory.afterPropertiesSet();
        return factory;
    }

    private LeaderElectionMockContext createMockContext(RedisConnectionFactory connectionFactory) {
        return new LeaderElectionMockContext(connectionFactory);
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    private EmbeddedRedisServer getRedisServer(ExtensionContext context) {
        return getStore(context).get(REDIS_SERVER_KEY, EmbeddedRedisServer.class);
    }

    private RedisConnectionFactory getConnectionFactory(ExtensionContext context) {
        return getStore(context).get(CONNECTION_FACTORY_KEY, RedisConnectionFactory.class);
    }

    private RedisTestSupport getTestSupport(ExtensionContext context) {
        return getStore(context).get(TEST_SUPPORT_KEY, RedisTestSupport.class);
    }

    private LeaderElectionMockContext getMockContext(ExtensionContext context) {
        return getStore(context).get(MOCK_CONTEXT_KEY, LeaderElectionMockContext.class);
    }

}
