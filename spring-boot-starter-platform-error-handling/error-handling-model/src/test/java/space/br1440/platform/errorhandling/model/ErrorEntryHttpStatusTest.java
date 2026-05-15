package space.br1440.platform.errorhandling.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ErrorEntry httpStatus")
class ErrorEntryHttpStatusTest {

    @Test
    void getHttpStatus_throwsWhenUnset() {
        // given
        ErrorEntry entry = ErrorEntry.builder()
                .errorCode("X-1")
                .message("m")
                .build();

        // when / then
        assertThat(entry.httpStatusOrNull()).isNull();
        assertThatThrownBy(entry::getHttpStatus)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("httpStatus");
    }

    @Test
    void getHttpStatus_returnsValueWhenSet() {
        // given
        ErrorEntry entry = ErrorEntry.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errorCode("X-1")
                .message("m")
                .build();

        // when / then
        assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entry.httpStatusOrNull()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
