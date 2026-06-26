package space.br1440.platform.errorhandling.core.strategy.cause;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.webflux.properties.ErrorHandlingUpstreamProperties;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebFluxCauseExceptionStrategy — empty metadata edges")
class WebFluxCauseExceptionStrategyEmptyMetadataTest {

    private WebFluxCauseExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new WebFluxCauseExceptionStrategy(
                new StackHintExtractor(false, List.of()),
                new ErrorHandlingUpstreamProperties()
        );
    }

    @Test
    @DisplayName("RequestException с URI без host → upstreamServiceName key omitted (kill EQUAL_IF на hasText(host))")
    void requestException_uriWithoutHost_omitsServiceName() {
        // given
        WebClientRequestException ex = new WebClientRequestException(
                new IOException("io-fail"), HttpMethod.GET,
                URI.create("/api/local"), HttpHeaders.EMPTY);

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag)
                .as("При host=null ключ upstreamServiceName должен отсутствовать")
                .doesNotContainKey("upstreamServiceName");
        assertThat(diag).containsKey("httpMethod");
        assertThat(diag).containsEntry("errorCategory", "network");
    }

    @Test
    @DisplayName("RequestException с URI без path → upstreamUrlPath key omitted (kill EQUAL_IF на hasText(path))")
    void requestException_uriWithoutPath_omitsUrlPath() {
        // given
        WebClientRequestException ex = new WebClientRequestException(
                new IOException("io-fail"), HttpMethod.GET,
                URI.create("http://svc"), HttpHeaders.EMPTY
        );

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag)
                .as("При пустом path ключ upstreamUrlPath должен отсутствовать")
                .doesNotContainKey("upstreamUrlPath");
        assertThat(diag).containsEntry("upstreamServiceName", "svc");
    }

    @Test
    @DisplayName("RequestException с cause без message → causeMessage key omitted")
    void requestException_emptyCauseMessage_omitsCauseMessage() {
        // given
        WebClientRequestException ex = new WebClientRequestException(
                new RuntimeException(), HttpMethod.GET,
                URI.create("http://svc/api"), HttpHeaders.EMPTY);

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag).containsKey("exceptionClass");
        assertThat(diag).containsEntry("networkFailureType", "UNKNOWN_NETWORK");
    }

    @Test
    @DisplayName("ResponseException без attached request → httpMethod/upstreamServiceName/upstreamUrlPath omitted")
    void responseException_withoutRequest_omitsRequestKeys() {
        // given
        WebClientResponseException ex = WebClientResponseException.create(
                502, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag)
                .as("Без request httpMethod не должен попасть в metadata")
                .doesNotContainKey("httpMethod");
        assertThat(diag)
                .as("Без request upstreamServiceName не должен попасть в metadata")
                .doesNotContainKey("upstreamServiceName");
        assertThat(diag)
                .as("Без request upstreamUrlPath не должен попасть в metadata")
                .doesNotContainKey("upstreamUrlPath");
        assertThat(diag).containsEntry("upstreamStatus", "502");
    }

    @Test
    @DisplayName("ResponseException с request URI без host → upstreamServiceName key omitted")
    void responseException_requestUriWithoutHost_omitsServiceName() {
        // given
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.POST, URI.create("/some/local"));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, req
        );

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag).doesNotContainKey("upstreamServiceName");
        assertThat(diag).containsEntry("httpMethod", "POST");
    }

    @Test
    @DisplayName("ResponseException с request URI без path → upstreamUrlPath key omitted")
    void responseException_requestUriWithoutPath_omitsUrlPath() {
        // given
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc"));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8, req
        );

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag).doesNotContainKey("upstreamUrlPath");
        assertThat(diag).containsEntry("upstreamServiceName", "svc");
    }

    @Test
    @DisplayName("ResponseException без request-id header → upstreamRequestId key omitted")
    void responseException_withoutRequestIdHeader_omitsKey() {
        // given
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Content-Type", "application/json");
        responseHeaders.set("Some-Other-Header", "irrelevant");
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", responseHeaders, new byte[0], StandardCharsets.UTF_8, req
        );

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag)
                .as("Без request-id headers ключ upstreamRequestId должен отсутствовать")
                .doesNotContainKey("upstreamRequestId");
    }

    @Test
    @DisplayName("ResponseException с пустым (whitespace-only) request-id header → ключ omitted")
    void responseException_blankRequestIdHeader_omitsKey() {
        // given
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("X-Request-Id", "   ");
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", responseHeaders, new byte[0], StandardCharsets.UTF_8, req
        );

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag)
                .as("hasText на whitespace-only вернёт false → ключ не должен попасть")
                .doesNotContainKey("upstreamRequestId");
    }

    @Test
    @DisplayName("ResponseException без Content-Length header → responseBodyLength key omitted (kill EQUAL_IF на declared >= 0)")
    void responseException_withoutContentLength_omitsBodyLengthKey() {
        // given
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Content-Type", "application/json");
        MockClientHttpRequest req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://svc/api"));
        WebClientResponseException ex = WebClientResponseException.create(
                500, "ISE", responseHeaders, new byte[0], StandardCharsets.UTF_8, req);

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag)
                .as("Без content-length и с пустым body responseBodyLength должен отсутствовать")
                .doesNotContainKey("responseBodyLength");
    }

    @Test
    @DisplayName("ResponseException с непустым telltale-cause без message → causeMessage всё равно может попасть")
    void responseException_emptyCauseMessage_doesNotEmitCauseMessage() {
        // given
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("Content-Type", "application/json");
        MockClientHttpRequest req = new MockClientHttpRequest(
                HttpMethod.GET, URI.create("http://svc/api")
        );
        WebClientResponseException ex = WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "X",
                responseHeaders, new byte[0], StandardCharsets.UTF_8, req
        );

        // when
        Map<String, String> diag = diagnostic(strategy.extractCauseDetails(ex));

        // then
        assertThat(diag).containsEntry("upstreamStatus", "500");
    }

    private static Map<String, String> diagnostic(RootCauseResolution result) {
        return result.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .map(ErrorInfoDetail::getDiagnosticMetadata)
                .orElseThrow(() -> new IllegalStateException("ErrorInfoDetail not found"));
    }
}
