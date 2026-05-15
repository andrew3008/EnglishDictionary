package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpringSecurityExceptionStrategy")
class SpringSecurityExceptionStrategyTest {

    private SpringSecurityExceptionStrategy strategy;

    private static ErrorHandlingContext context(String path) {
        return ErrorHandlingContext.of(
                new ErrorHandlingProperties(), path, new StackHintExtractor(false, List.of()));
    }

    @BeforeEach
    void setUp() {
        CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
        strategy = new SpringSecurityExceptionStrategy(resolver, LogPolicy.WARN, LogPolicy.WARN);
    }

    @Nested
    @DisplayName("supports")
    class Supports {

        @ParameterizedTest(name = "{0}")
        @MethodSource("supportedExceptions")
        void acceptsSecurityExceptions(String label, Exception exception) {
            // when / then
            assertThat(strategy.supports(exception)).isTrue();
        }

        static Stream<Arguments> supportedExceptions() {
            return Stream.of(
                    Arguments.of("BadCredentialsException",
                            new BadCredentialsException("bad")),
                    Arguments.of("InsufficientAuthenticationException",
                            new InsufficientAuthenticationException("insufficient")),
                    Arguments.of("AccountExpiredException",
                            new AccountExpiredException("expired")),
                    Arguments.of("CredentialsExpiredException",
                            new CredentialsExpiredException("creds")),
                    Arguments.of("DisabledException",
                            new DisabledException("disabled")),
                    Arguments.of("LockedException",
                            new LockedException("locked")),
                    Arguments.of("AccessDeniedException",
                            new AccessDeniedException("denied")),
                    Arguments.of("Custom AuthenticationException subclass",
                            new AuthenticationServiceException("service error"))
            );
        }

        @Test
        void rejectsUnrelatedExceptions() {
            // when / then
            assertThat(strategy.supports(new RuntimeException("unrelated"))).isFalse();
            assertThat(strategy.supports(new IllegalArgumentException("bad arg"))).isFalse();
            assertThat(strategy.supports(new NullPointerException())).isFalse();
        }
    }

    @Nested
    @DisplayName("authentication handling")
    class AuthenticationHandling {

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("authenticationReasonCodes")
        void mapsAuthenticationSubtypeToCorrectReason(String label,
                                                      String expectedReason,
                                                      AuthenticationException exception) {
            // when
            ErrorEntry entry = strategy.handle(exception, context("/api/secure"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(entry.getErrorCode()).isEqualTo("UNAUTHENTICATED");
            assertThat(entry.getReason()).isEqualTo(expectedReason);

            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getReason()).isEqualTo(expectedReason);
            assertThat(info.getDomain()).isEqualTo("security");
        }

        static Stream<Arguments> authenticationReasonCodes() {
            return Stream.of(
                    Arguments.of("BadCredentialsException", "BAD_CREDENTIALS",
                            new BadCredentialsException("wrong password")),
                    Arguments.of("InsufficientAuthenticationException", "INSUFFICIENT_AUTHENTICATION",
                            new InsufficientAuthenticationException("need full auth")),
                    Arguments.of("AccountExpiredException", "ACCOUNT_EXPIRED",
                            new AccountExpiredException("account expired")),
                    Arguments.of("CredentialsExpiredException", "CREDENTIALS_EXPIRED",
                            new CredentialsExpiredException("creds expired")),
                    Arguments.of("DisabledException", "ACCOUNT_DISABLED",
                            new DisabledException("account disabled")),
                    Arguments.of("LockedException", "ACCOUNT_LOCKED",
                            new LockedException("account locked")),
                    Arguments.of("Unknown AuthenticationException subclass", "AUTHENTICATION_FAILED",
                            new AuthenticationServiceException("unknown auth failure"))
            );
        }

        @Test
        void publicMessageIsNeutral_cwe203() {
            // given
            BadCredentialsException exception = new BadCredentialsException("password=s3cret123!");

            // when
            ErrorEntry entry = strategy.handle(exception, context("/login"));

            // then
            assertThat(entry.getMessage())
                    .isEqualTo("Full authentication is required to access this resource");
            assertThat(entry.getMessage()).doesNotContain("s3cret123!");
            assertThat(entry.getMessage()).doesNotContain("password");
        }

        @Test
        void clientMetadataContainsReasonOnly() {
            // given
            BadCredentialsException exception = new BadCredentialsException("internal detail");

            // when
            ErrorEntry entry = strategy.handle(exception, context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getClientMetadata())
                    .containsEntry("reason", "BAD_CREDENTIALS")
                    .hasSize(1);
        }

        @Test
        void requestPathIsPreserved() {
            // when
            ErrorEntry entry = strategy.handle(
                    new BadCredentialsException("x"), context("/api/v2/auth"));

            // then
            assertThat(entry.getRequestURI()).isEqualTo("/api/v2/auth");
        }
    }

    @Nested
    @DisplayName("access denied handling")
    class AccessDeniedHandling {

        @Test
        void returnsCorrectStatusAndErrorCode() {
            // when
            ErrorEntry entry = strategy.handle(
                    new AccessDeniedException("forbidden resource"), context("/admin"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(entry.getErrorCode()).isEqualTo("PERMISSION_DENIED");
            assertThat(entry.getReason()).isEqualTo("Access denied");
        }

        @Test
        void publicMessageIsNeutral_cwe203() {
            // given
            AccessDeniedException exception = new AccessDeniedException(
                    "Access to /admin/users requires ROLE_ADMIN");

            // when
            ErrorEntry entry = strategy.handle(exception, context("/admin/users"));

            // then
            assertThat(entry.getMessage())
                    .isEqualTo("You do not have permission to access this resource");
            assertThat(entry.getMessage()).doesNotContain("ROLE_ADMIN");
        }

        @Test
        void noClientMetadata() {
            // when
            ErrorEntry entry = strategy.handle(
                    new AccessDeniedException("denied"), context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getClientMetadata()).isNullOrEmpty();
        }

        @Test
        void domainIsSecurity() {
            // when
            ErrorEntry entry = strategy.handle(
                    new AccessDeniedException("denied"), context("/"));

            // then
            ErrorInfoDetail info = extractFirstInfoDetail(entry);
            assertThat(info.getDomain()).isEqualTo("security");
        }
    }

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTests {

        @Test
        void authenticationException_usesConfiguredAuthPolicy() {
            // given
            SpringSecurityExceptionStrategy custom = new SpringSecurityExceptionStrategy(
                    new CauseChainResolver(Collections.emptyList()),
                    LogPolicy.ERROR,
                    LogPolicy.DEBUG);
            ErrorEntry entry = custom.handle(
                    new BadCredentialsException("x"), context("/"));

            // when / then
            assertThat(custom.logPolicy(new BadCredentialsException("x"), entry))
                    .isEqualTo(LogPolicy.ERROR);
        }

        @Test
        void accessDeniedException_usesConfiguredAccessDeniedPolicy() {
            // given
            SpringSecurityExceptionStrategy custom = new SpringSecurityExceptionStrategy(
                    new CauseChainResolver(Collections.emptyList()),
                    LogPolicy.ERROR,
                    LogPolicy.DEBUG);
            ErrorEntry entry = custom.handle(
                    new AccessDeniedException("x"), context("/"));

            // when / then
            assertThat(custom.logPolicy(new AccessDeniedException("x"), entry))
                    .isEqualTo(LogPolicy.DEBUG);
        }

        @Test
        void defaultPoliciesAreWarn() {
            // given
            ErrorEntry dummyEntry = strategy.handle(
                    new BadCredentialsException("x"), context("/"));

            // when / then
            assertThat(strategy.logPolicy(new BadCredentialsException(""), dummyEntry))
                    .isEqualTo(LogPolicy.WARN);
            assertThat(strategy.logPolicy(new AccessDeniedException(""), dummyEntry))
                    .isEqualTo(LogPolicy.WARN);
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        void orderMatchesSecurityConstant() {
            // when / then
            assertThat(strategy.getOrder()).isEqualTo(TopExceptionStrategyOrder.SECURITY);
        }
    }

    private static ErrorInfoDetail extractFirstInfoDetail(ErrorEntry entry) {
        assertThat(entry.getDetails()).isNotEmpty();
        return entry.getDetails().stream()
                .filter(d -> d instanceof ErrorInfoDetail)
                .map(d -> (ErrorInfoDetail) d)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No ErrorInfoDetail found in entry"));
    }
}
