package space.br1440.platform.tracing.api.span.spec;

import jakarta.annotation.Nullable;

/**
 * lifecycle-handle database span.
 * <p>
 * Код ответа БД пишется в {@code db.response.status_code}. Если операция затем
 * завершается ошибкой, тот же код становится {@code error.type}. Имя класса
 * исключения остаётся на событии {@code exception} в {@code exception.type}.
 * <p>
 * Handle нельзя передавать между потоками: и {@link #responseStatusCode(String)},
 * и {@link #close()} вызываются в потоке, который получил handle.
 */
public interface DatabaseSpanHandle extends SpanHandle {

    /**
     * Принимает код ответа БД.
     * <p>
     * {@code null}, пустая строка, строка из пробелов и значение вне допуска
     * не меняют уже принятый код и не бросают исключение. Допуск: длина от 1
     * до 64, цифры, латинские буквы, {@code '-'} и {@code '/'}.
     */
    void responseStatusCode(@Nullable String code);

}
