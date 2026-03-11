package space.br1440.platform.healthchecks.minio;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.messages.Bucket;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.util.StringUtils;
import space.br1440.platform.healthchecks.async.AsyncHealthIndicator;
import space.br1440.platform.healthchecks.common.HealthCheckTimer;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.ProbeAware;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MinioHealthIndicator extends AsyncHealthIndicator implements ProbeAware {

    private final MinioClient minioClient;
    private final MinioHealthProperties properties;
    private final MinioSentinelHealthCheck sentinelHealthCheck;
    private final MinioBucketHealthCheck bucketHealthCheck;

    public MinioHealthIndicator(MinioClient minioClient,
                                MinioHealthProperties properties,
                                MinioSentinelHealthCheck sentinelHealthCheck,
                                MinioBucketHealthCheck bucketHealthCheck) {
        super(properties.getAsync());
        this.minioClient = minioClient;
        this.properties = properties;
        this.sentinelHealthCheck = sentinelHealthCheck;
        this.bucketHealthCheck = bucketHealthCheck;
    }

    @Override
    public Set<HealthProbe> getProbes() {
        return properties.getProbes();
    }

    @Override
    protected Health doHealthCheck() {
        HealthCheckTimer timer = HealthCheckTimer.start();
        Health.Builder builder = Health.up();
        Map<String, Object> details = new HashMap<>();

        try {
            details.put("service", "MinIO");

            String probeBucket = properties.getBuckets().getProbeBucket();
            if (StringUtils.hasText(probeBucket)) {
                checkBucketExists(probeBucket, details);
            } else {
                checkConnectivity(details);
            }

            bucketHealthCheck.checkBuckets(details);

            if (properties.getSentinelCheck().isEnabled()) {
                sentinelHealthCheck.check(details);
            }

            return timer.finalize(builder, details, properties.getTimeout().getHealthCheckTimeout());
        } catch (Exception e) {
            return timer.finalize(
                    builder.down()
                            .withDetail("error", e.getMessage())
                            .withDetail("errorType", e.getClass().getSimpleName()),
                    details,
                    properties.getTimeout().getHealthCheckTimeout()
            );
        }
    }

    @Override
    protected Status getFailureHealthCheckStatus() {
        return Status.DOWN;
    }

    private void checkBucketExists(String bucketName, Map<String, Object> details) throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
        );

        details.put("checkType", "bucketExists");
        details.put("bucket", bucketName);
        details.put("exists", exists);

        if (!exists) {
            throw new IllegalStateException("Health check bucket does not exist: " + bucketName);
        }
    }

    private void checkConnectivity(Map<String, Object> details) throws Exception {
        List<Bucket> buckets = minioClient.listBuckets();
        details.put("checkType", "connectivity");
        details.put("bucketsCount", buckets.size());

        if (!buckets.isEmpty()) {
            List<String> sample = buckets.stream()
                    .map(Bucket::name)
                    .limit(5)
                    .toList();
            details.put("sampleBuckets", sample);
        }
    }
}
