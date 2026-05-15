package space.br1440.platform.errorhandling.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.dto.ErrorEntryDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.BadRequestDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.ErrorInfoDetailDTO;
import space.br1440.platform.errorhandling.model.error_detail.dto.RetryInfoDetailDTO;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorHandlingMvcRuntimeHints")
class ErrorHandlingMvcRuntimeHintsTest {

    private final ErrorHandlingMvcRuntimeHints registrar = new ErrorHandlingMvcRuntimeHints();

    @Test
    @DisplayName("Все ключевые DTO зарегистрированы для рефлексии (Jackson)")
    void registersAllDtoTypes() {
        // given
        RuntimeHints hints = new RuntimeHints();

        // when
        registrar.registerHints(hints, getClass().getClassLoader());

        // then
        assertThat(RuntimeHintsPredicates.reflection().onType(ErrorEntryDTO.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(ErrorInfoDetailDTO.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(BadRequestDetailDTO.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(RetryInfoDetailDTO.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(ErrorEntry.class)).accepts(hints);
    }

    @Test
    @DisplayName("Null-безопасность: registerHints(null, _) не падает")
    void nullHints_doesNotThrow() {
        // when / then
        registrar.registerHints(null, getClass().getClassLoader());
    }
}
