package space.br1440.platform.errorhandling.autoconfigure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.storage.RedisExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
public class ErrorHandlingRedisConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.lettuce.core.RedisException")
    static class LettuceRedisConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "redisExceptionStrategy")
        public RootCauseExceptionHandlingStrategy redisExceptionStrategy(StackHintExtractor stackHintExtractor) {
            return new RedisExceptionStrategy(stackHintExtractor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "redis.clients.jedis.exceptions.JedisException")
    @ConditionalOnMissingBean(name = "redisExceptionStrategy")
    static class JedisRedisConfiguration {
        @Bean
        public RootCauseExceptionHandlingStrategy redisExceptionStrategy(StackHintExtractor stackHintExtractor) {
            return new RedisExceptionStrategy(stackHintExtractor);
        }
    }
}