package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.healthchecks.minio.exception.SentinelObjectNotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class MinioSentinelHealthCheck {

    private final MinioClient minioClient;
    private final MinioHealthProperties properties;

    public void check(Map<String, Object> details) {
        MinioHealthProperties.SentinelCheck config = properties.getSentinelCheck();
        if (!config.isEnabled()) {
            return;
        }

        Map<String, Object> sentinelDetails = new LinkedHashMap<>();
        sentinelDetails.put("sentinelBucket", config.getSentinelBucket());
        sentinelDetails.put("sentinelObjectKey", config.getSentinelObjectKey());

        long startTime = System.currentTimeMillis();
        try {
            verifySentinelObjectExists(config);
            sentinelDetails.put("status", "reachable");
        } catch (SentinelObjectNotFoundException e) {
            log.warn("Minio sentinel object not found: bucket={}, key={}", config.getSentinelBucket(), config.getSentinelObjectKey());
            sentinelDetails.put("status", "sentinel_missing");
            sentinelDetails.put("error", e.getMessage());
        } catch (Exception e) {
            log.error("Minio sentinel check failed: bucket={}, key={}", config.getSentinelBucket(), config.getSentinelObjectKey(), e);
            sentinelDetails.put("status", "error");
            sentinelDetails.put("error", e.getMessage());
            sentinelDetails.put("errorType", e.getClass().getSimpleName());
        } finally {
            sentinelDetails.put("responseTime", System.currentTimeMillis() - startTime + "ms");
        }

        details.put("sentinelCheck", sentinelDetails);
    }

    private void verifySentinelObjectExists(MinioHealthProperties.SentinelCheck config) throws Exception {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(config.getSentinelBucket())
                            .object(config.getSentinelObjectKey())
                            .build()
            );
        } catch (ErrorResponseException e) {
            if ("NoSuchBucket".equals(e.errorResponse().code()) || "NoSuchKey".equals(e.errorResponse().code())) {
                throw new SentinelObjectNotFoundException(
                        "Sentinel object not found: %s/%s".formatted(config.getSentinelBucket(), config.getSentinelObjectKey())
                );
            }
            throw e;
        }
    }
}
