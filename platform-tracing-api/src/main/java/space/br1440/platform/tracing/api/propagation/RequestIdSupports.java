package space.br1440.platform.tracing.api.propagation;

import lombok.experimental.UtilityClass;

import java.util.ServiceLoader;

/**
 * Единая точка разрешения {@link RequestIdSupport} через {@link ServiceLoader} SPI.
 * <p>
 * Call-site'ы ({@code InboundTraceControlExtractor}, response-header filters и др.)
 * получают реализацию ({@code RequestIdSupportImpl} из {@code platform-tracing-core})
 * через {@code META-INF/services}, не завися от конкретного класса напрямую.
 * <p>
 * <b>Требование к runtime classpath:</b> {@code platform-tracing-core}
 * (или test-double, зарегистрированный под тем же service interface) должен
 * присутствовать в runtime classpath. При его отсутствии static initializer
 * бросит {@link IllegalStateException} на этапе загрузки класса.
 */
@UtilityClass
public final class RequestIdSupports {

    private static final RequestIdSupport INSTANCE =
            ServiceLoader.load(RequestIdSupport.class)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("""
                            No RequestIdSupport implementation found on classpath.
                            Ensure platform-tracing-core is present at runtime.
                            """));

    public static RequestIdSupport get() {
        return INSTANCE;
    }

}
