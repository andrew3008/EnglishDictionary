package space.br1440.platform.healthchecks.configuration;

import io.minio.MinioClient;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.healthchecks.minio.MinioBucketHealthCheck;
import space.br1440.platform.healthchecks.minio.MinioHealthIndicator;
import space.br1440.platform.healthchecks.minio.MinioHealthProperties;
import space.br1440.platform.healthchecks.minio.MinioSentinelHealthCheck;

@Configuration(proxyBeanMethods = false)
// MinIO SDK подключается потребителем при необходимости; класс резолвим по имени,
// чтобы автоконфигурация молча отключалась при отсутствии `io.minio:minio` в classpath.
@ConditionalOnClass(name = "io.minio.MinioClient")
@ConditionalOnBean(MinioClient.class)
@ConditionalOnProperty(prefix = MinioHealthProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(name = "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration")
@EnableConfigurationProperties(MinioHealthProperties.class)
public class MinioHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MinioBucketHealthCheck minioBucketHealthCheck(MinioClient minioClient,
                                                         MinioHealthProperties properties) {
        return new MinioBucketHealthCheck(minioClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MinioSentinelHealthCheck minioOperationsHealthCheck(MinioClient minioClient,
                                                               MinioHealthProperties properties) {
        return new MinioSentinelHealthCheck(minioClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public HealthIndicator minioHealthIndicator(MinioClient minioClient,
                                                MinioHealthProperties properties,
                                                MinioBucketHealthCheck bucketHealthCheck,
                                                MinioSentinelHealthCheck minioOperationsHealthCheck) {
        return new MinioHealthIndicator(minioClient, properties, minioOperationsHealthCheck, bucketHealthCheck);
    }
}
