package space.br1440.platform.healthchecks.minio;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class MinioBucketHealthCheck {

    private final MinioClient minioClient;
    private final MinioHealthProperties properties;

    public void checkBuckets(Map<String, Object> details) {
        List<String> monitoredBuckets = properties.getBuckets().getMonitoredBuckets();
        if (monitoredBuckets == null || monitoredBuckets.isEmpty()) {
            return;
        }

        Map<String, Object> bucketsMap = new LinkedHashMap<>();
        boolean hasWarnings = false;

        for (String bucketName : monitoredBuckets) {
            Map<String, Object> bucketInfo = new LinkedHashMap<>();
            try {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucketName).build()
                );
                bucketInfo.put("exists", exists);
                bucketInfo.put("accessible", exists);

                if (!exists) {
                    bucketInfo.put("warning", "Bucket does not exist: " + bucketName);
                    hasWarnings = true;
                }
            } catch (Exception e) {
                bucketInfo.put("exists", false);
                bucketInfo.put("accessible", false);
                bucketInfo.put("warning", e.getMessage());
                hasWarnings = true;
            }
            bucketsMap.put(bucketName, bucketInfo);
        }

        details.put("buckets", bucketsMap);
        if (hasWarnings) {
            details.put("bucketsWarning", "One or more monitored buckets are unavailable");
        }
    }
}
