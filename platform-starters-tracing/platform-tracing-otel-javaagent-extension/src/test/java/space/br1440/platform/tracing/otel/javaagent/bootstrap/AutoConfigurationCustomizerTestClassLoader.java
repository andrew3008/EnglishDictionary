package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

final class AutoConfigurationCustomizerTestClassLoader extends ClassLoader {

    private static final String CUSTOMIZER_SERVICE =
            "META-INF/services/" + AutoConfigurationCustomizerProvider.class.getName();

    private AutoConfigurationCustomizerTestClassLoader(ClassLoader parent) {
        super(parent);
    }

    static ClassLoader withoutDiscoveredCustomizers() {
        return new AutoConfigurationCustomizerTestClassLoader(
                AutoConfigurationCustomizerTestClassLoader.class.getClassLoader());
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // Ручные customizer-тесты сохраняют порядок регистрации и не дублируют production SPI.
        if (CUSTOMIZER_SERVICE.equals(name)) {
            return Collections.emptyEnumeration();
        }
        return super.getResources(name);
    }
}
