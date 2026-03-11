package space.br1440.platform.healthchecks.minio.support;

import io.minio.*;
import io.minio.messages.Item;
import lombok.experimental.UtilityClass;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import space.br1440.platform.healthchecks.minio.MinioBucketHealthCheck;
import space.br1440.platform.healthchecks.minio.MinioHealthIndicator;
import space.br1440.platform.healthchecks.minio.MinioHealthProperties;
import space.br1440.platform.healthchecks.minio.MinioSentinelHealthCheck;

import java.io.ByteArrayInputStream;
import java.time.Duration;

@UtilityClass
public final class MinioContainerSupport {

    public static final String MINIO_IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z";
    public static final String USERNAME = "minioadmin";
    public static final String PASSWORD = "minioadmin";

    public static final MinIOContainer MINIO;
    public static final MinioClient CLIENT;

    static {
        MINIO = new MinIOContainer(DockerImageName.parse(MINIO_IMAGE))
                .withUserName(USERNAME)
                .withPassword(PASSWORD)
                .withStartupTimeout(Duration.ofMinutes(2));
        MINIO.start();

        CLIENT = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(USERNAME, PASSWORD)
                .build();
    }

    public static MinioHealthProperties baseProperties() {
        MinioHealthProperties props = new MinioHealthProperties();
        props.getAsync().setEnabled(false);
        return props;
    }

    public static MinioHealthIndicator indicator(MinioHealthProperties props) {
        return indicator(CLIENT, props);
    }

    public static MinioHealthIndicator indicator(MinioClient client, MinioHealthProperties props) {
        props.getAsync().setEnabled(false);
        return new MinioHealthIndicator(
                client, props,
                new MinioSentinelHealthCheck(client, props),
                new MinioBucketHealthCheck(client, props));
    }

    public static void createBucket(String name) throws Exception {
        CLIENT.makeBucket(MakeBucketArgs.builder().bucket(name).build());
    }

    public static void removeBucket(String bucket) throws Exception {
        Iterable<Result<Item>> objects = CLIENT.listObjects(
                ListObjectsArgs.builder().bucket(bucket).recursive(true).build()
        );

        for (Result<Item> result : objects) {
            CLIENT.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(result.get().objectName())
                            .build()
            );
        }

        CLIENT.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
    }

    public static void putSentinelObject(String bucket, String objectKey) throws Exception {
        byte[] empty = new byte[0];
        CLIENT.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(new ByteArrayInputStream(empty), 0, -1)
                        .contentType("application/octet-stream")
                        .build()
        );
    }
}
