package space.br1440.platform.tracing.api.propagation;

import lombok.experimental.UtilityClass;

import java.util.ServiceLoader;

/**
 * Единая точка разрешения {@link OtelTraceparentReader} через {@link ServiceLoader} SPI.
 * <p>
 * Обе builder-точки ({@code DefaultSpanSpecBuilder} в api и
 * {@code AbstractSemanticSpanBuilder} в core) получают реализацию
 * ({@code OtelTraceparentReaderImpl} из {@code platform-tracing-core})
 * через {@code META-INF/services}, не завися от конкретного класса напрямую.
 * <p>
 * <b>Требование к runtime classpath:</b> {@code platform-tracing-core}
 * (или test-double, зарегистрированный под тем же service interface) должен
 * присутствовать в runtime classpath. При его отсутствии static initializer
 * бросит {@link IllegalStateException} на этапе загрузки класса.
 * <p>
 * Прикладной код не должен вызывать этот holder напрямую — используйте
 * builder-метод {@code fromTraceparent(...)}.
 */
@UtilityClass
public final class OtelTraceparentReaders {

    private static final OtelTraceparentReader INSTANCE =
            ServiceLoader.load(OtelTraceparentReader.class)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("""
                                 No OtelTraceparentReader implementation found on classpath.
                                 Ensure platform-tracing-core is present at runtime.
                                 """));

    public static OtelTraceparentReader get() {
        return INSTANCE;
    }
}
