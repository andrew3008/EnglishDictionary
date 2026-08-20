package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

class PlatformOwnedHttpServletResponseTest {

    @Test
    void overwriteAndCommitKeepsCanonicalValue() throws Exception {
        // given
        MockHttpServletResponse delegate = new MockHttpServletResponse();
        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(delegate);
        wrapped.protectTraceId("canonical-trace");
        wrapped.protectRequestId(PlatformHeaders.X_REQUEST_ID, "canonical-request");
        wrapped.installCanonical();

        // when
        wrapped.setHeader(PlatformHeaders.X_TRACE_ID, "evil");
        wrapped.setHeader("x-trace-id", "evil-lower");
        wrapped.setHeader("X-TRACE-ID", "evil-upper");
        wrapped.setHeader("x-request-id", "evil-request");
        wrapped.setHeader(PlatformHeaders.X_TRACE_ID, null);
        wrapped.setIntHeader(PlatformHeaders.X_TRACE_ID, 123);
        wrapped.addIntHeader(PlatformHeaders.X_REQUEST_ID, 456);
        wrapped.setDateHeader(PlatformHeaders.X_TRACE_ID, 1L);
        wrapped.addDateHeader(PlatformHeaders.X_REQUEST_ID, 2L);
        wrapped.flushBuffer();

        // then
        assertThat(caseInsensitiveValues(delegate, PlatformHeaders.X_TRACE_ID))
                .containsExactly("canonical-trace");
        assertThat(caseInsensitiveValues(delegate, PlatformHeaders.X_REQUEST_ID))
                .containsExactly("canonical-request");
    }

    @Test
    void resetReinstallsCanonicalHeaders() {
        // given
        MockHttpServletResponse delegate = new MockHttpServletResponse();
        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(delegate);
        wrapped.protectTraceId("canonical-trace");
        wrapped.installCanonical();
        wrapped.setHeader("X-App", "keep-me-until-reset");

        // when
        wrapped.reset();

        // then
        assertThat(caseInsensitiveValues(delegate, PlatformHeaders.X_TRACE_ID))
                .containsExactly("canonical-trace");
        assertThat(delegate.getHeader("X-App")).isNull();
    }

    @Test
    void resetBufferKeepsCanonicalHeadersWithoutReinstall() {
        // given
        MockHttpServletResponse delegate = new MockHttpServletResponse();
        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(delegate);
        wrapped.protectTraceId("canonical-trace");
        wrapped.installCanonical();

        // when
        wrapped.resetBuffer();

        // then
        assertThat(caseInsensitiveValues(delegate, PlatformHeaders.X_TRACE_ID))
                .containsExactly("canonical-trace");
    }

    @Test
    void unprotectedWhenTraceIdAbsentAllowsDownstreamWrite() {
        // given
        MockHttpServletResponse delegate = new MockHttpServletResponse();
        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(delegate);

        // when
        wrapped.setHeader(PlatformHeaders.X_TRACE_ID, "app-trace");

        // then
        assertThat(delegate.getHeader(PlatformHeaders.X_TRACE_ID)).isEqualTo("app-trace");
    }

    @Test
    void unprotectedWhenRequestIdDisabledAllowsDownstreamWrite() {
        // given
        MockHttpServletResponse delegate = new MockHttpServletResponse();
        PlatformOwnedHttpServletResponse wrapped = new PlatformOwnedHttpServletResponse(delegate);
        wrapped.protectTraceId("canonical-trace");
        wrapped.installCanonical();

        // when
        wrapped.setHeader(PlatformHeaders.X_REQUEST_ID, "app-request");

        // then
        assertThat(delegate.getHeader(PlatformHeaders.X_REQUEST_ID)).isEqualTo("app-request");
        assertThat(caseInsensitiveValues(delegate, PlatformHeaders.X_TRACE_ID))
                .containsExactly("canonical-trace");
    }

    private static List<String> caseInsensitiveValues(MockHttpServletResponse response, String name) {
        return response.getHeaderNames().stream()
                .filter(candidate -> candidate.equalsIgnoreCase(name))
                .flatMap(candidate -> response.getHeaders(candidate).stream())
                .toList();
    }
}
