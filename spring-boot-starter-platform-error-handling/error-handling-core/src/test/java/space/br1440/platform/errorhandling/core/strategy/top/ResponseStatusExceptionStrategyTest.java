package space.br1440.platform.errorhandling.core.strategy.top;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean;
import space.br1440.platform.errorhandling.extension.HttpStatusResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResponseStatusExceptionStrategyTest {

    @Mock
    private ResponseStatusResolverBean resolverBean;

    private ResponseStatusExceptionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ResponseStatusExceptionStrategy(resolverBean, new CauseChainResolver(Collections.emptyList()));
    }

    @Test
    @DisplayName("supports() — true, если резолвер вернул непустой Optional")
    void supports_true_whenResolverPresent() {
        // given
        when(resolverBean.resolve(any(), anyInt()))
                .thenReturn(Optional.of(new HttpStatusResolution(HttpStatus.GONE, "Gone")));

        // when / then
        assertThat(strategy.supports(new RuntimeException())).isTrue();
    }

    @Test
    @DisplayName("supports() — false, если резолвер вернул пустой Optional")
    void supports_false_whenResolverEmpty() {
        // given
        when(resolverBean.resolve(any(), anyInt())).thenReturn(Optional.empty());

        // when / then
        assertThat(strategy.supports(new RuntimeException())).isFalse();
    }

    @Test
    @DisplayName("handle() для 4xx — message = reasonPhrase (developer-controlled), не exception.getMessage()")
    void handle_4xx_usesReasonPhraseAsMessage() {
        // given
        when(resolverBean.resolve(any(), anyInt()))
                .thenReturn(Optional.of(
                        new HttpStatusResolution(HttpStatus.CONFLICT, "Custom conflict reason"))
                );

        // when
        var entry = strategy.handle(new RuntimeException("SQL: DROP TABLE users"), testContext());

        // then
        assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(entry.getMessage()).isEqualTo("Custom conflict reason");
        assertThat(entry.getReason()).isEqualTo("Custom conflict reason");
        assertThat(entry.getMessage()).doesNotContain("SQL");
    }

    @Test
    @DisplayName("handle() для 5xx — message = стандартный HTTP reason phrase, не exception.getMessage() (CWE-209)")
    void handle_5xx_usesGenericMessage_notExceptionMessage() {
        // given
        when(resolverBean.resolve(any(), anyInt()))
                .thenReturn(Optional.of(
                        new HttpStatusResolution(HttpStatus.INTERNAL_SERVER_ERROR, "Custom 500 reason"))
                );

        // when
        var entry = strategy.handle(
                new RuntimeException("Failed at jdbc:oracle:thin:@prod-db:1521/USERS"), testContext()
        );

        // then
        assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(entry.getMessage()).isEqualTo("Internal Server Error");
        assertThat(entry.getMessage()).doesNotContain("jdbc");
        assertThat(entry.getMessage()).doesNotContain("oracle");
    }

    @Test
    @DisplayName("handle() бросает исключение, если резолвер вернул пусто (нарушение контракта)")
    void handle_throws_whenResolverEmpty() {
        // given
        when(resolverBean.resolve(any(), anyInt())).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> strategy.handle(new RuntimeException(), testContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP-статус не удалось резолвнуть");
    }

    @Test
    @DisplayName("резолвер вызывается с тем же исключением и фиксированной ROUTING_MAX_DEPTH=10")
    void handle_passesExceptionToResolver_withRoutingDepth() {
        // given
        var ex = new RuntimeException("specific");
        when(resolverBean.resolve(any(), anyInt())).thenReturn(Optional.of(
                new HttpStatusResolution(HttpStatus.BAD_REQUEST, "bad")));

        // when
        strategy.handle(ex, testContext());

        // then
        verify(resolverBean).resolve(eq(ex), eq(10));
    }

    @Test
    @DisplayName("doHandle() использует ROUTING_MAX_DEPTH, а не properties.maxCauseDepth — routing invariant")
    void handle_usesRoutingDepth_notPropertiesMaxCauseDepth() {
        // given
        var ex = new RuntimeException("deep chain");
        when(resolverBean.resolve(any(), anyInt())).thenReturn(Optional.of(
                new HttpStatusResolution(HttpStatus.NOT_FOUND, "Not Found")));

        ErrorHandlingProperties props = new ErrorHandlingProperties();
        props.setMaxCauseDepth(3);
        ErrorHandlingContext ctx = ErrorHandlingContext.of(
                props, "/api/test", new StackHintExtractor(false, List.of()));

        // when
        strategy.handle(ex, ctx);

        // then
        verify(resolverBean).resolve(eq(ex), eq(10));
    }

    @Test
    @DisplayName("exception.getMessage() попадает в diagnosticMetadata, а не в client-facing поля")
    void handle_exceptionMessage_inDiagnosticMetadata_notInClientFields() {
        // given
        String sensitiveMsg = "ORA-00942: table USERS_PII does not exist";
        when(resolverBean.resolve(any(), anyInt()))
                .thenReturn(Optional.of(
                        new HttpStatusResolution(HttpStatus.INTERNAL_SERVER_ERROR, "DB error"))
                );

        // when
        var entry = strategy.handle(new RuntimeException(sensitiveMsg), testContext());

        // then
        assertThat(entry.getMessage()).doesNotContain("ORA-00942");
        assertThat(entry.getMessage()).doesNotContain("USERS_PII");

        assertThat(entry.getDetails()).isNotEmpty();
        var info = (ErrorInfoDetail)entry.getDetails().getFirst();
        assertThat(info.getDiagnosticMetadata())
                .containsEntry("exceptionMessage", sensitiveMsg);
    }

    @Test
    @DisplayName("getOrder() возвращает 900")
    void order_is900() {
        // when / then
        assertThat(strategy.getOrder()).isEqualTo(900);
    }

    private static ErrorHandlingContext testContext() {
        ErrorHandlingProperties props = new ErrorHandlingProperties();
        return ErrorHandlingContext.of(props, "/api/test", new StackHintExtractor(false, List.of()));
    }

}
