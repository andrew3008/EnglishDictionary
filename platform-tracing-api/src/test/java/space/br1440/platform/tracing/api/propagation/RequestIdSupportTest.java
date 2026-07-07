package space.br1440.platform.tracing.api.propagation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestIdSupport: edge-stable correlation id (validate + reject-and-regenerate)")
class RequestIdSupportTest {

    @Test
    @DisplayName("отсутствующий входящий -> генерируется валидный UUIDv4")
    void generatesUuidWhenAbsent() {
        String id = RequestIdSupport.resolve(null);
        assertThat(id).isNotBlank();
        assertThat(RequestIdSupport.sanitizeOrNull(id)).isEqualTo(id); // сгенерированный проходит валидацию
    }

    @Test
    @DisplayName("валидный входящий переиспользуется без изменений (forward unchanged)")
    void reusesValidIncoming() {
        assertThat(RequestIdSupport.resolve("req-123_ABC")).isEqualTo("req-123_ABC");
    }

    @Test
    @DisplayName("CRLF отклоняется (CWE-113) -> генерируется новый")
    void rejectsCrlf() {
        assertThat(RequestIdSupport.sanitizeOrNull("abc\r\nSet-Cookie: x")).isNull();
        String resolved = RequestIdSupport.resolve("abc\r\nSet-Cookie: x");
        assertThat(resolved).doesNotContain("\r").doesNotContain("\n");
        assertThat(RequestIdSupport.sanitizeOrNull(resolved)).isEqualTo(resolved);
    }

    @Test
    @DisplayName("превышение длины -> reject (не truncate)")
    void rejectsOversized() {
        String oversized = "a".repeat(RequestIdSupport.MAX_LENGTH + 1);
        assertThat(RequestIdSupport.sanitizeOrNull(oversized)).isNull();
    }

    @Test
    @DisplayName("control-символы и пробелы внутри значения отклоняются")
    void rejectsControlChars() {
        assertThat(RequestIdSupport.sanitizeOrNull("ab cd")).isNull();
        assertThat(RequestIdSupport.sanitizeOrNull("ab\u0000cd")).isNull();
    }
}
