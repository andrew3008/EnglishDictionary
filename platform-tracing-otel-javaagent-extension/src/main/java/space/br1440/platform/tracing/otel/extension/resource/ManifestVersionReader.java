package space.br1440.platform.tracing.otel.extension.resource;

import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.io.InputStream;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Tertiary fallback для {@code service.version}: читает {@code Implementation-Version} из
 * {@code META-INF/MANIFEST.MF} (паттерн {@code ManifestResourceProvider}, order=300 в OTel).
 * <p>
 * Используется, только если версия не определена ни явной конфигурацией, ни
 * {@link BuildInfoReader}. Любая ошибка → {@link Optional#empty()}.
 */
public final class ManifestVersionReader {

    static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
    static final String IMPL_VERSION_KEY = "Implementation-Version";

    private final ClasspathResourceLoader loader;

    public ManifestVersionReader() {
        this(ClasspathResourceLoader.tcclFirst());
    }

    public ManifestVersionReader(ClasspathResourceLoader loader) {
        this.loader = loader;
    }

    /**
     * @return значение {@code Implementation-Version}, либо {@link Optional#empty()}
     */
    public Optional<String> readVersion() {
        try (InputStream in = loader.open(MANIFEST_PATH)) {
            if (in == null) {
                return Optional.empty();
            }
            Manifest manifest = new Manifest(in);
            Attributes attrs = manifest.getMainAttributes();
            String version = attrs.getValue(IMPL_VERSION_KEY);
            if (Strings.isNotBlank(version)) {
                return Optional.of(version.trim());
            }
            return Optional.empty();
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }
}
