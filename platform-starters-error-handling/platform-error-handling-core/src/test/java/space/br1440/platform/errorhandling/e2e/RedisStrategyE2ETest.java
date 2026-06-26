package space.br1440.platform.errorhandling.e2e;

import io.lettuce.core.RedisException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.RedisExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@Tag("testcontainers")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = RedisStrategyE2ETest.TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("RedisExceptionStrategy — E2E (Testcontainers + Spring Data Redis)")
class RedisStrategyE2ETest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisExceptionStrategy strategy;

    @Nested
    @DisplayName("Real Lettuce WRONGTYPE via StringRedisTemplate")
    class RealRedisWrongType {

        @Test
        @DisplayName("hash get on string key yields RedisException in cause chain")
        void wrongTypeOnStringKey() {
            String key = "e2e:redis-wrong-type";
            redis.opsForValue().set(key, "scalar");

            Throwable thrown = catchThrowable(() -> redis.opsForHash().get(key, "field"));
            assertThat(thrown).isNotNull();

            RedisException lettuce = unwrapRedisException(thrown);
            assertThat(lettuce).isNotNull();
            assertThat(strategy.supportsCause(lettuce)).isTrue();
        }
    }

    @Nested
    @DisplayName("RedisExceptionStrategy on real exception")
    class StrategyHandlesRealException {

        @Test
        @DisplayName("extractCauseDetails returns non-empty ErrorInfoDetail")
        void extractCauseDetails_returnsErrorInfo() {
            String key = "e2e:redis-strategy";
            redis.opsForValue().set(key, "x");
            Throwable thrown = catchThrowable(() -> redis.opsForHash().get(key, "f"));
            assertThat(thrown).isNotNull();

            RootCauseResolution resolution = strategy.extractCauseDetails(thrown);
            assertThat(resolution.httpStatus()).isNotNull();
            assertThat(resolution.details().stream().anyMatch(ErrorInfoDetail.class::isInstance)).isTrue();
        }
    }

    private static RedisException unwrapRedisException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RedisException redisEx) {
                return redisEx;
            }
            current = current.getCause();
        }
        return null;
    }

    @SpringBootConfiguration
    @Import(RedisAutoConfiguration.class)
    static class TestConfig {

        @Bean
        StackHintExtractor stackHintExtractor() {
            return new StackHintExtractor(false, List.of());
        }

        @Bean
        RedisExceptionStrategy redisExceptionStrategy(StackHintExtractor stackHintExtractor) {
            return new RedisExceptionStrategy(stackHintExtractor);
        }
    }
}
