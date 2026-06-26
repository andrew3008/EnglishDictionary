package space.br1440.platform.errorhandling.autoconfigure.enterprise;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.core.strategy.cause.enterprise.MinioExceptionStrategy;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.minio.errors.MinioException")
public class ErrorHandlingMinioConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "minioExceptionStrategy")
    public RootCauseExceptionHandlingStrategy minioExceptionStrategy(StackHintExtractor stackHintExtractor) {
        return new MinioExceptionStrategy(stackHintExtractor);
    }
}