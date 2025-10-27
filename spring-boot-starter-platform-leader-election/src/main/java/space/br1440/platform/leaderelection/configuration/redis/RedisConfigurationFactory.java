package space.br1440.platform.leaderelection.configuration.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties.RedisSettings;

import java.time.Duration;

@Slf4j
@UtilityClass
public final class RedisConfigurationFactory {

    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofMillis(100);
    private static final Duration DEFAULT_SOCKET_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    public static RedisConnectionFactory createConnectionFactory(LeaderElectionProperties properties) {
        RedisSettings redisSettings = properties.getRedis();
        return createStandaloneConnectionFactory(redisSettings);
    }

    private static RedisConnectionFactory createStandaloneConnectionFactory(RedisSettings settings) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(settings.getHost());
        config.setPort(settings.getPort());
        config.setDatabase(settings.getDatabase());
        configureAuthentication(config, settings);

        LettuceClientConfiguration clientConfig = buildClientConfiguration();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    private static void configureAuthentication(RedisStandaloneConfiguration config, RedisSettings settings) {
        if (StringUtils.hasText(settings.getUsername())) {
            config.setUsername(settings.getUsername());
        }

        if (StringUtils.hasText(settings.getPassword())) {
            config.setPassword(RedisPassword.of(settings.getPassword()));
        }
    }

    private static LettuceClientConfiguration buildClientConfiguration() {
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(DEFAULT_SOCKET_CONNECT_TIMEOUT)
                        .build())
                .timeoutOptions(TimeoutOptions.enabled())
                .build();

        ClientResources clientResources = DefaultClientResources.builder().build();
        return LettuceClientConfiguration.builder()
                .commandTimeout(DEFAULT_COMMAND_TIMEOUT)
                .shutdownTimeout(DEFAULT_SHUTDOWN_TIMEOUT)
                .clientOptions(clientOptions)
                .clientResources(clientResources)
                .build();
    }

}
