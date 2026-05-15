package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebFluxExceptionStrategy — buildDiagnosticMetadata empty edges (Sprint 5 it.3)")
class WebFluxExceptionStrategyEmptyMetadataTest {

    private WebFluxExceptionStrategy strategy;
    private ErrorHandlingContext context;

    @BeforeEach
    void setUp() {
        strategy = new WebFluxExceptionStrategy(new CauseChainResolver(Collections.emptyList()));
        context = ErrorHandlingContext.of(
                new ErrorHandlingProperties(), "/api/test",
                new StackHintExtractor(false, List.of()));
    }

    @Test
    @DisplayName("ResponseException без request → upstreamHost / upstreamUrlPath omitted (kill EQUAL_IF на request != null)")
    void responseException_withoutRequest_omitsHostAndPath() {
        // given
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

        // when
        ErrorEntry entry = strategy.handle(ex, context);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag)
                .as("Без attached request upstreamHost не должен попасть в diagnostic")
                .doesNotContainKey("upstreamHost");
        assertThat(diag)
                .as("Без attached request upstreamUrlPath не должен попасть")
                .doesNotContainKey("upstreamUrlPath");

        assertThat(diag).containsEntry("upstreamStatus", "500");
    }

    @Test
    @DisplayName("ResponseException с request URI без host → upstreamHost key omitted (kill EQUAL_IF на hasText(host))")
    void responseException_requestUriWithoutHost_omitsHost() {
        // given
        MockClientHttpRequest req = new MockClientHttpRequest(
                HttpMethod.GET, URI.create("/api/foo"));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, req);

        // when
        ErrorEntry entry = strategy.handle(ex, context);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag).doesNotContainKey("upstreamHost");
        assertThat(diag).containsEntry("upstreamUrlPath", "/api/foo");
    }

    @Test
    @DisplayName("ResponseException с request URI без path → upstreamUrlPath key omitted (kill EQUAL_IF на hasText(path))")
    void responseException_requestUriWithoutPath_omitsUrlPath() {
        // given
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc"));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, req
        );

        // when
        ErrorEntry entry = strategy.handle(ex, context);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag).doesNotContainKey("upstreamUrlPath");
        assertThat(diag).containsEntry("upstreamHost", "svc");
    }

    @Test
    @DisplayName("ResponseException с request URI host=null AND path=пустой → оба ключа omitted (комбинация)")
    void responseException_requestUriWithoutHostAndWithoutPath_omitsBoth() {
        // given
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create(""));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, req
        );

        // when
        ErrorEntry entry = strategy.handle(ex, context);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag).doesNotContainKey("upstreamHost");
        assertThat(diag).doesNotContainKey("upstreamUrlPath");
    }

    @Test
    @DisplayName("Exception с непустым stackHint=disabled → stackHint key omitted (kill EQUAL_IF на hasText(stackHint))")
    void exceptionWithDisabledStackHint_omitsStackHintKey() {
        // given
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8
        );

        // when
        ErrorEntry entry = strategy.handle(ex, context);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag)
                .as("При disabled stackHint extractor ключ stackHint не должен попасть")
                .doesNotContainKey("stackHint");
    }

    @Test
    @DisplayName("Exception со stackHint=enabled → stackHint key ПРИСУТСТВУЕТ (kill EQUAL_ELSE/NullReturn на hasText(stackHint))")
    void exceptionWithEnabledStackHint_addsStackHintKey() {
        // given
        StackHintExtractor enabled = new StackHintExtractor(true, List.of());
        ErrorHandlingContext withStackHint = ErrorHandlingContext.of(new ErrorHandlingProperties(), "/api/with-stack", enabled);
        IllegalStateException ex = new IllegalStateException("with-stack-hint");

        // when
        ErrorEntry entry = strategy.handle(ex, withStackHint);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag)
                .as("При enabled StackHintExtractor ключ stackHint обязан присутствовать")
                .containsKey("stackHint");
        assertThat(diag.get("stackHint"))
                .as("stackHint должен содержать non-empty frame-список")
                .isNotBlank();
    }

    @Test
    @DisplayName("RuntimeException (не WebClientResponseException) → handle возвращает не-null entry (kill NullReturnVals на enrichWithUpstreamErrorCode early-return)")
    void runtimeException_notWebClient_returnsNonNullEntry() {
        // given
        IllegalStateException ex = new IllegalStateException("local-business-error");

        // when
        ErrorEntry entry = strategy.handle(ex, context);

        // then
        assertThat(entry)
                .as("handle() должен возвращать не-null entry для любого Exception")
                .isNotNull();
        assertThat(entry.getDetails())
                .as("Details должны быть инициализированы (не null) даже для не-WebClient ошибок")
                .isNotNull();
    }

    @Test
    @DisplayName("WebClientResponseException с reason → causeMessage добавляется (kill EQUAL_ELSE на hasText(causeMessage))")
    void responseException_withCauseMessage_addsCauseMessageKey() {
        // given
        WebClientResponseException ex = WebClientResponseException.create(
                500, "Service Unavailable Reason",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

        // when
        ErrorEntry entry = strategy.handle(ex, context);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag)
                .as("Для exception с непустым message ключ causeMessage обязан присутствовать")
                .containsKey("causeMessage");
        assertThat(diag.get("causeMessage"))
                .as("causeMessage должен содержать оригинальный текст")
                .contains("500");
    }

    @Test
    @DisplayName("Exception с nested cause → root-cause keys присутствуют (kill VoidMethodCall на enrichWithRootCause)")
    void exceptionWithNestedCause_addsRootCauseKeys() {
        // given
        Throwable rootCause = new IllegalArgumentException("very-root-explanation");
        Throwable middle = new RuntimeException("middle-wrapper", rootCause);
        IllegalStateException top = new IllegalStateException("top-level-wrapper", middle);

        // when
        ErrorEntry entry = strategy.handle(top, context);

        // then
        Map<String, String> diag = diagnostic(entry);

        assertThat(diag)
                .as("После enrichWithRootCause должны появиться ключи о root cause")
                .satisfiesAnyOf(
                        d -> assertThat(d).containsKey("rootCauseClass"),
                        d -> assertThat(d).containsKey("rootCauseMessage"),
                        d -> assertThat(d).containsKey("causeChain"),
                        d -> assertThat(d).containsKey("rootCause")
                );
    }

    private static Map<String, String> diagnostic(ErrorEntry entry) {
        return entry.getDetails().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .orElseThrow(() -> new IllegalStateException("ErrorInfoDetail not found"));
    }
}
