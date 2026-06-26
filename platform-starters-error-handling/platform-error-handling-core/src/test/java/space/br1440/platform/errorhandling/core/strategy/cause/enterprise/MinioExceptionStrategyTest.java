package space.br1440.platform.errorhandling.core.strategy.cause.enterprise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MinioExceptionStrategy")
class MinioExceptionStrategyTest {

    private final MinioExceptionStrategy strategy =
            new MinioExceptionStrategy(new StackHintExtractor(false, List.of()));

    @Test
    @DisplayName("supports io.minio.errors.MinioException by class name")
    void supportsMinioException() throws Exception {
        Class<?> clazz = Class.forName("io.minio.errors.MinioException");
        Throwable ex = (Throwable) clazz.getConstructor(String.class).newInstance("access denied");
        assertThat(strategy.supportsCause(ex)).isTrue();
    }

    @Test
    @DisplayName("generic MinioException → 500 with object-storage domain")
    void genericMinio() throws Exception {
        Class<?> clazz = Class.forName("io.minio.errors.MinioException");
        Throwable ex = (Throwable) clazz.getConstructor(String.class).newInstance("Invalid credentials");
        RootCauseResolution resolution = strategy.extractCauseDetails(ex);
        assertThat(resolution.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        ErrorInfoDetail info = resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(info.getDomain()).isEqualTo("object-storage");
    }
}