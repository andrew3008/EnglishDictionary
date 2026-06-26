package space.br1440.platform.errorhandling.testing.support;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.UnknownExceptionHandler;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.List;

/**
 * Тестовая реализация {@link UnknownExceptionHandler}, возвращающая фиксированный 500 ответ.
 */
public class TestUnknownExceptionHandler implements UnknownExceptionHandler {

    @Override
    @NonNull
    public @NotNull ErrorEntry handle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason("UNKNOWN_INTERNAL_ERROR")
                .build();

        return ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode("UNKNOWN_INTERNAL_ERROR")
                .message("Unknown error")
                .details(List.of(info))
                .build();
    }
}
