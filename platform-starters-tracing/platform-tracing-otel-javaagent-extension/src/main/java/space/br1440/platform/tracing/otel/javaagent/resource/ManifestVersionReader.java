package space.br1440.platform.tracing.otel.javaagent.resource;

import static java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import space.br1440.platform.common.util.StringUtils;

public final class ManifestVersionReader {

    static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";

    private final ClasspathResourceLoader loader;

    public ManifestVersionReader() {
        this(ClasspathResourceLoader.tcclFirst());
    }

    public ManifestVersionReader(ClasspathResourceLoader loader) {
        this.loader = loader;
    }

    public Optional<String> readVersion() {
        return openManifest()
                .flatMap(this::extractVersion);
    }

    private Optional<Manifest> openManifest() {
        try (InputStream in = loader.open(MANIFEST_PATH)) {
            if (in == null) {
                return Optional.empty();
            }

            return Optional.of(new Manifest(in));
        }

        catch (IOException | RuntimeException | LinkageError ex) {
            return Optional.empty();
        }
    }

    private Optional<String> extractVersion(Manifest manifest) {
        Attributes attributes = manifest.getMainAttributes();
        if (attributes == null) {
            return Optional.empty();
        }

        String version = attributes.getValue(IMPLEMENTATION_VERSION);
        return Optional.ofNullable(version)
                .map(String::trim)
                .filter(value -> !StringUtils.isNullOrBlank(value));
    }
}
