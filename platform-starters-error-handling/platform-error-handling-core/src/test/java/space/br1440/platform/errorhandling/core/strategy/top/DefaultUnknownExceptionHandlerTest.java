package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultUnknownExceptionHandler")
class DefaultUnknownExceptionHandlerTest {

    private static ErrorHandlingContext context(String path) {
        return ErrorHandlingContext.of(
                new ErrorHandlingProperties(), path, new StackHintExtractor(false, List.of()));
    }

    @Nested
    @DisplayName("handle")
    class Handle {

        @Test
        void neutralPublicMessageAndCweSafePayload() {
            // given
            CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
            DefaultUnknownExceptionHandler handler = new DefaultUnknownExceptionHandler(resolver);

            // when
            var entry = handler.handle(new IllegalArgumentException("secret internal"), context("/api/v1/x"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(entry.getErrorCode()).isEqualTo(CanonicalErrors.UNKNOWN_INTERNAL_ERROR);
            assertThat(entry.getMessage()).isEqualTo("Internal server error");
            assertThat(entry.getMessage()).doesNotContain("secret");
            assertThat(entry.getReason()).contains(IllegalArgumentException.class.getName());
        }

        @Test
        void reason_containsFqcnOnly_notMessage() {
            // CWE-209: reason содержит FQCN без getMessage() — обеспечивает
            // однозначную идентификацию в логах и не раскрывает внутренние детали.
            // given
            CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
            DefaultUnknownExceptionHandler handler = new DefaultUnknownExceptionHandler(resolver);

            // when
            var entry = handler.handle(
                    new RuntimeException("wrapper", new IllegalStateException("deep-reason")),
                    context("/"));

            // then
            assertThat(entry.getReason()).isEqualTo(IllegalStateException.class.getName());
            assertThat(entry.getReason()).doesNotContain("deep-reason");
        }

        @Test
        void deepCauseMessage_goesToDiagnosticMetadata() {
            // CWE-209 fix: getMessage() попадает в diagnosticMetadata ErrorInfoDetail, не в reason
            // given
            CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
            DefaultUnknownExceptionHandler handler = new DefaultUnknownExceptionHandler(resolver);

            // when
            var entry = handler.handle(
                    new RuntimeException("wrapper", new IllegalStateException("deep-reason")),
                    context("/"));

            // then
            var infoDetail = entry.getDetails().stream()
                    .filter(d -> d instanceof space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail)
                    .map(d -> (space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail) d)
                    .findFirst()
                    .orElseThrow();
            assertThat(infoDetail.getDiagnosticMetadata()).containsEntry("rootCauseMessage", "deep-reason");
        }

        @Test
        void mergesDetailsFromCauseChainResolverOnImmediateCause(@Mock RootCauseExceptionHandlingStrategy strategy) {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any()))
                    .thenAnswer(inv -> inv.getArgument(0, Throwable.class) instanceof IllegalStateException);
            ErrorInfoDetail fromResolver = ErrorInfoDetail.builder()
                    .reason("FROM_RESOLVER")
                    .domain("test")
                    .build();
            when(strategy.extractCauseDetails(any())).thenReturn(
                    RootCauseResolution.of(HttpStatus.BAD_REQUEST, "from-strategy", List.of(fromResolver)));

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            DefaultUnknownExceptionHandler handler = new DefaultUnknownExceptionHandler(resolver);

            IllegalStateException inner = new IllegalStateException("inner");
            RuntimeException top = new RuntimeException("top", inner);

            // when
            var entry = handler.handle(top, context("/"));

            // then
            assertThat(entry.getDetails()).hasSize(2);
            assertThat(entry.getDetails().get(1)).isInstanceOf(ErrorInfoDetail.class);
            assertThat(((ErrorInfoDetail) entry.getDetails().get(1)).getReason()).isEqualTo("FROM_RESOLVER");
        }

        @Test
        void noExtraCauseDetailsWhenNoCause() {
            // given
            CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
            DefaultUnknownExceptionHandler handler = new DefaultUnknownExceptionHandler(resolver);

            // when
            var entry = handler.handle(new IllegalArgumentException("only"), context("/"));

            // then
            assertThat(entry.getDetails()).hasSize(1);
        }

        @Test
        void mergesDetailsWhenRecognizedExceptionIsTopLevel(@Mock RootCauseExceptionHandlingStrategy strategy) {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any()))
                    .thenAnswer(inv -> inv.getArgument(0, Throwable.class) instanceof IllegalStateException);
            ErrorInfoDetail fromResolver = ErrorInfoDetail.builder()
                    .reason("FROM_RESOLVER")
                    .domain("http-integration")
                    .build();
            when(strategy.extractCauseDetails(any())).thenReturn(
                    RootCauseResolution.of(HttpStatus.SERVICE_UNAVAILABLE, "from-strategy", List.of(fromResolver)));

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            DefaultUnknownExceptionHandler handler = new DefaultUnknownExceptionHandler(resolver);

            // when
            var entry = handler.handle(new IllegalStateException("upstream 503"), context("/"));

            // then
            assertThat(entry.getDetails()).hasSize(2);
            assertThat(((ErrorInfoDetail) entry.getDetails().get(1)).getDomain()).isEqualTo("http-integration");
        }

        @Test
        void chainResolutionNeverOverridesFiveHundredStatus(@Mock RootCauseExceptionHandlingStrategy strategy) {
            // given
            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any()))
                    .thenAnswer(inv -> inv.getArgument(0, Throwable.class) instanceof IllegalStateException);
            when(strategy.extractCauseDetails(any())).thenReturn(
                    RootCauseResolution.of(HttpStatus.SERVICE_UNAVAILABLE, "from-strategy", List.of()));

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            DefaultUnknownExceptionHandler handler = new DefaultUnknownExceptionHandler(resolver);

            // when
            var deepest = handler.handle(new RuntimeException("top", new IllegalStateException("deep")), context("/"));
            var topLevel = handler.handle(new IllegalStateException("top"), context("/"));

            // then
            assertThat(deepest.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(topLevel.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
