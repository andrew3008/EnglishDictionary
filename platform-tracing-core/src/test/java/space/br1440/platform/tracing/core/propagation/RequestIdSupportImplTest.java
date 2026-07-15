package space.br1440.platform.tracing.core.propagation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.RequestIdSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link RequestIdSupportImpl} через интерфейс {@link RequestIdSupport}.
 */
@DisplayName("RequestIdSupportImpl: edge-stable correlation id (validate + reject-and-regenerate)")
class RequestIdSupportImplTest {

    private static final RequestIdSupport SUPPORT = RequestIdSupportImpl.INSTANCE;

    @Test
    @DisplayName("отсутствующий входящий -> генерируется валидный UUIDv4")
    void generatesUuidWhenAbsent() {
        String id = SUPPORT.resolve(null);
        assertThat(id).isNotBlank();
        assertThat(SUPPORT.sanitizeOrNull(id)).isEqualTo(id);
    }

    @Test
    @DisplayName("валидный входящий переиспользуется без изменений (forward unchanged)")
    void reusesValidIncoming() {
        assertThat(SUPPORT.resolve("req-123_ABC")).isEqualTo("req-123_ABC");
    }

    @Test
    @DisplayName("CRLF отклоняется (CWE-113) -> генерируется новый")
    void rejectsCrlf() {
        assertThat(SUPPORT.sanitizeOrNull("abc\r\nSet-Cookie: x")).isNull();
        String resolved = SUPPORT.resolve("abc\r\nSet-Cookie: x");
        assertThat(resolved).doesNotContain("\r").doesNotContain("\n");
        assertThat(SUPPORT.sanitizeOrNull(resolved)).isEqualTo(resolved);
    }

    @Test
    @DisplayName("превышение длины -> reject (не truncate)")
    void rejectsOversized() {
        String oversized = "a".repeat(RequestIdSupport.MAX_LENGTH + 1);
        assertThat(SUPPORT.sanitizeOrNull(oversized)).isNull();
    }

    @Test
    @DisplayName("control-символы и пробелы внутри значения отклоняются")
    void rejectsControlChars() {
        assertThat(SUPPORT.sanitizeOrNull("ab cd")).isNull();
        assertThat(SUPPORT.sanitizeOrNull("ab\u0000cd")).isNull();
    }
}
