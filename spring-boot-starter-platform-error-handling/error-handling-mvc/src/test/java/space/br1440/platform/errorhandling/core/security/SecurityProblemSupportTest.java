package space.br1440.platform.errorhandling.core.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import space.br1440.platform.errorhandling.testing.MockSecurityTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockSecurityTest
@DisplayName("SecurityProblemSupport")
class SecurityProblemSupportTest {

    private static final String CORRELATION_ID = "sec-corr-001";

    private HandlerExceptionResolver resolver;
    private SecurityProblemSupport support;

    @BeforeEach
    void setUp() {
        MDC.clear();
        resolver = mock(HandlerExceptionResolver.class);
        support = new SecurityProblemSupport(resolver);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private MockHttpServletRequest requestWithCorrelation() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secure");
        request.addHeader("X-Correlation-Id", CORRELATION_ID);
        return request;
    }

    @Nested
    @DisplayName("AuthenticationEntryPoint")
    class AuthenticationEntryPointTests {

        @Test
        @DisplayName("commence() делегирует AuthenticationException в HandlerExceptionResolver")
        void commence_delegatesToResolver() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any()))
                    .thenReturn(new ModelAndView());
            AuthenticationException exception = new BadCredentialsException("bad creds");
            MockHttpServletRequest request = requestWithCorrelation();
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            support.commence(request, response, exception);

            // then
            verify(resolver).resolveException(eq(request), eq(response), isNull(), eq(exception));
        }

        @Test
        @DisplayName("MDC очищается после вызова commence()")
        void commence_clearsMdc() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any()))
                    .thenReturn(new ModelAndView());
            AuthenticationException exception = new BadCredentialsException("bad creds");

            // when
            support.commence(requestWithCorrelation(), new MockHttpServletResponse(), exception);

            // then
            assertThat(MDC.get("correlationId")).isNull();
            assertThat(MDC.get("exceptionType")).isNull();
        }
    }

    @Nested
    @DisplayName("AccessDeniedHandler")
    class AccessDeniedHandlerTests {

        @Test
        @DisplayName("handle() делегирует AccessDeniedException в HandlerExceptionResolver")
        void handle_delegatesToResolver() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any()))
                    .thenReturn(new ModelAndView());
            AccessDeniedException exception = new AccessDeniedException("no access");
            MockHttpServletRequest request = requestWithCorrelation();
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            support.handle(request, response, exception);

            // then
            verify(resolver).resolveException(eq(request), eq(response), isNull(), eq(exception));
        }

        @Test
        @DisplayName("MDC очищается после вызова handle()")
        void handle_clearsMdc() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any()))
                    .thenReturn(new ModelAndView());
            AccessDeniedException exception = new AccessDeniedException("no access");

            // when
            support.handle(requestWithCorrelation(), new MockHttpServletResponse(), exception);

            // then
            assertThat(MDC.get("correlationId")).isNull();
            assertThat(MDC.get("exceptionType")).isNull();
        }
    }

    @Nested
    @DisplayName("Fallback ответ")
    class FallbackResponse {

        @Test
        @DisplayName("При resolver=null (не смог обработать) -- 401 fallback JSON для AuthenticationException")
        void resolverReturnsNull_writes401Fallback() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(null);
            AuthenticationException exception = new BadCredentialsException("bad creds");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            support.commence(requestWithCorrelation(), response, exception);

            // then
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getContentType()).contains("application/problem+json");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
            String body = response.getContentAsString();
            assertThat(body).contains("\"httpCode\":401");
            assertThat(body).contains("\"reason\":\"UNAUTHENTICATED\"");
        }

        @Test
        @DisplayName("При resolver=null -- 403 fallback JSON для AccessDeniedException")
        void resolverReturnsNull_writes403Fallback() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(null);
            AccessDeniedException exception = new AccessDeniedException("no access");
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            support.handle(requestWithCorrelation(), response, exception);

            // then
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            String body = response.getContentAsString();
            assertThat(body).contains("\"httpCode\":403");
            assertThat(body).contains("\"reason\":\"PERMISSION_DENIED\"");
        }

        @Test
        @DisplayName("Fallback ответ содержит корректный charset UTF-8")
        void fallback_hasUtf8Charset() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(null);
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            support.commence(requestWithCorrelation(), response,
                    new BadCredentialsException("bad creds"));

            // then
            assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        }
    }

    @Nested
    @DisplayName("Ошибка в resolver")
    class ResolverFailure {

        @Test
        @DisplayName("При exception из resolver -- записывается fallback response")
        void resolverThrows_writesFallbackResponse() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any()))
                    .thenThrow(new RuntimeException("resolver crash"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            // when
            support.commence(requestWithCorrelation(), response,
                    new BadCredentialsException("bad creds"));

            // then
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getContentAsString()).contains("\"reason\":\"UNAUTHENTICATED\"");
        }

        @Test
        @DisplayName("MDC очищается даже при exception из resolver")
        void resolverThrows_mdcCleared() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any()))
                    .thenThrow(new RuntimeException("resolver crash"));

            // when
            support.commence(requestWithCorrelation(), new MockHttpServletResponse(),
                    new BadCredentialsException("bad creds"));

            // then
            assertThat(MDC.get("correlationId")).isNull();
            assertThat(MDC.get("exceptionType")).isNull();
        }
    }

    @Nested
    @DisplayName("Committed response")
    class CommittedResponse {

        @Test
        @DisplayName("Если response.isCommitted() -- ничего не записывается")
        void committedResponse_nothingWritten() throws Exception {
            // given
            when(resolver.resolveException(any(), any(), isNull(), any())).thenReturn(null);
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.getOutputStream().write("already written".getBytes());
            response.flushBuffer();

            // when
            support.commence(requestWithCorrelation(), response,
                    new BadCredentialsException("bad creds"));

            // then
            assertThat(response.isCommitted()).isTrue();
        }
    }
}
