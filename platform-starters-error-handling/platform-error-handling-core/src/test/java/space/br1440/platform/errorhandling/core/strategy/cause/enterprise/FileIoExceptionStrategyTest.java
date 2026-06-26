package space.br1440.platform.errorhandling.core.strategy.cause.enterprise;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NotDirectoryException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileIoExceptionStrategy")
class FileIoExceptionStrategyTest {

    private FileIoExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FileIoExceptionStrategy(new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("Generic IOException — transient network detection")
    class GenericIoTransientNetwork {

        @Test
        void connectionReset_isRetryable_andTransientReason() {
            // given
            IOException ex = new IOException("Connection reset");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(hasRetryInfo(details)).isTrue();
            assertThat(diagnostic(details)).containsEntry("retryableReason", "TRANSIENT_NETWORK");
            assertThat(diagnostic(details)).containsEntry("exceptionClass", IOException.class.getName());
        }

        @Test
        void withConnectExceptionCause_isRetryable() {
            // given
            IOException ex = new IOException("wrapped", new ConnectException("Connection refused"));

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(hasRetryInfo(details)).isTrue();
            assertThat(diagnostic(details)).containsEntry("retryableReason", "TRANSIENT_NETWORK");
        }
    }

    @Nested
    @DisplayName("Generic IOException — non-transient")
    class GenericIoNonTransient {

        @Test
        void noSpaceLeft_notRetryable() {
            // given
            IOException ex = new IOException("No space left on device");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(hasRetryInfo(details)).isFalse();
            assertThat(diagnostic(details)).containsEntry("retryableReason", "NON_TRANSIENT");
        }

        @Test
        void unknownMessage_notRetryable() {
            // given
            IOException ex = new IOException("Something opaque");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(hasRetryInfo(details)).isFalse();
            assertThat(diagnostic(details)).containsEntry("retryableReason", "NON_TRANSIENT");
        }

        @Test
        void malformedUrlException_notTreatedAsTransientNetwork() {
            // given
            IOException ex = new MalformedURLException("no protocol: foo");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(hasRetryInfo(details)).isFalse();
            assertThat(diagnostic(details)).containsEntry("retryableReason", "NON_TRANSIENT");
        }
    }

    @Nested
    @DisplayName("FileSystemException")
    class FileSystemExceptions {

        @Test
        void accessDenied_includesFsReasonAndExtension() {
            // given
            FileSystemException ex = new AccessDeniedException("secret.dat", null, "Permission denied");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(diagnostic(details))
                    .containsEntry("fileExtension", "dat")
                    .containsKey("fsReason");
            assertThat(reason(details)).isEqualTo("ACCESS_DENIED");
        }

        @Test
        void unsafeFileName_omitsFileExtension() {
            // given
            FileSystemException ex = new AccessDeniedException("evil\ninjected", null, "x");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(diagnostic(details)).doesNotContainKey("fileExtension");
        }

        @Test
        void genericFsReason_isRetryable() {
            // given
            FileSystemException ex = new FileSystemException("file", null, "Device or resource busy");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(hasRetryInfo(details)).isTrue();
            assertThat(reason(details)).isEqualTo("FILESYSTEM_ERROR");
        }

        @Test
        void notADirectoryReason_isNotRetryable() {
            // given
            FileSystemException ex = new FileSystemException("file", null, "Not a directory");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(hasRetryInfo(details)).isFalse();
            assertThat(reason(details)).isEqualTo("FILESYSTEM_ERROR");
        }

        @Test
        void notDirectoryException_isNotRetryable() {
            // given
            IOException ex = new NotDirectoryException("/tmp/not-a-directory");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(hasRetryInfo(details)).isFalse();
            assertThat(reason(details)).isEqualTo("FILESYSTEM_ERROR");
        }
    }

    @Nested
    @DisplayName("Encoding & EOF exceptions")
    class EncodingAndEof {

        @Test
        void unsupportedEncoding_includesEncodingName() {
            // given
            IOException ex = new java.io.UnsupportedEncodingException("UTF-999");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(diagnostic(details))
                    .containsEntry("encodingName", "UTF-999")
                    .containsEntry("exceptionClass", java.io.UnsupportedEncodingException.class.getName());
        }

        @Test
        void charConversionException_unsafeMessage_omitsEncodingName() {
            // given
            IOException ex = new java.io.CharConversionException("evil\ninjected{\"key\":\"val\"}");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(diagnostic(details)).doesNotContainKey("encodingName");
        }

        @Test
        void charConversionException_safeMessage_includesEncodingName() {
            // given
            IOException ex = new java.io.CharConversionException("UTF-8");

            // when
            RootCauseResolution details = strategy.extractCauseDetails(ex);

            // then
            assertThat(details.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(diagnostic(details)).containsEntry("encodingName", "UTF-8");
        }
    }

    private static boolean hasRetryInfo(RootCauseResolution details) {
        return details.details().stream().anyMatch(RetryInfoDetail.class::isInstance);
    }

    private static String reason(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .map(ErrorInfoDetail::getReason)
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> diagnostic(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .findFirst()
                .orElseThrow();
    }

}
