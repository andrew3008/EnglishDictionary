package space.br1440.platform.tracing.runtime.propagation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;

class CorrelationIdSupportTest {

    @Test
    void requireCanonicalAcceptsValidGrammar() {
        // when/then
        assertThat(CorrelationIdSupport.requireCanonical("Valid_Id-123")).isEqualTo("Valid_Id-123");
    }

    @Test
    void requireCanonicalRejectsNull() {
        // when/then
        assertThatNullPointerException().isThrownBy(() -> CorrelationIdSupport.requireCanonical(null));
    }

    @Test
    void requireCanonicalRejectsEmptyAndTooLong() {
        // when/then
        assertThatIllegalArgumentException().isThrownBy(() -> CorrelationIdSupport.requireCanonical(""));
        assertThatIllegalArgumentException().isThrownBy(() -> CorrelationIdSupport.requireCanonical("a".repeat(129)));
    }

    @Test
    void requireCanonicalRejectsDisallowedCharacters() {
        // when/then
        assertThatIllegalArgumentException().isThrownBy(() -> CorrelationIdSupport.requireCanonical("has space"));
        assertThatIllegalArgumentException().isThrownBy(() -> CorrelationIdSupport.requireCanonical("has.dot"));
        assertThatIllegalArgumentException().isThrownBy(() -> CorrelationIdSupport.requireCanonical("has:colon"));
        assertThatIllegalArgumentException().isThrownBy(() -> CorrelationIdSupport.requireCanonical("a,b"));
    }

    @Test
    void resolveInboundReturnsNullWhenAbsent() {
        // when/then
        assertThat(CorrelationIdSupport.resolveInbound(null)).isNull();
        assertThat(CorrelationIdSupport.resolveInbound(List.of())).isNull();
    }

    @Test
    void resolveInboundAcceptsExactlyOneValidValue() {
        // when/then
        assertThat(CorrelationIdSupport.resolveInbound(List.of("trusted-correlation"))).isEqualTo("trusted-correlation");
    }

    @Test
    void resolveInboundRejectsDuplicateValuesAsAWhole() {
        // given: дублированный заголовок, включая регистро-разные имена, сведённые контейнером
        // в несколько значений одного canonical-имени
        List<String> duplicated = List.of("first-value", "second-value");

        // when/then
        assertThat(CorrelationIdSupport.resolveInbound(duplicated)).isNull();
    }

    @Test
    void resolveInboundRejectsCommaJoinedSingleValue() {
        // given: контейнер уже склеил несколько значений в одно comma-joined поле
        List<String> commaJoined = List.of("first-value,second-value");

        // when/then
        assertThat(CorrelationIdSupport.resolveInbound(commaJoined)).isNull();
    }

    @Test
    void resolveInboundDoesNotTrimWhitespace() {
        // given: пробелы не нормализуются — значение целиком валидно либо отклоняется
        List<String> withLeadingSpace = List.of(" leading-space");

        // when/then
        assertThat(CorrelationIdSupport.resolveInbound(withLeadingSpace)).isNull();
    }

    @Test
    void resolveInboundRejectsEmptyOrTooLongValue() {
        // when/then
        assertThat(CorrelationIdSupport.resolveInbound(List.of(""))).isNull();
        assertThat(CorrelationIdSupport.resolveInbound(List.of("a".repeat(129)))).isNull();
    }

    @Test
    void sanitizeOrNullTrimsAndValidates() {
        // when/then
        assertThat(CorrelationIdSupport.sanitizeOrNull(" valid-id ")).isEqualTo("valid-id");
        assertThat(CorrelationIdSupport.sanitizeOrNull(null)).isNull();
        assertThat(CorrelationIdSupport.sanitizeOrNull("has space inside")).isNull();
    }
}
