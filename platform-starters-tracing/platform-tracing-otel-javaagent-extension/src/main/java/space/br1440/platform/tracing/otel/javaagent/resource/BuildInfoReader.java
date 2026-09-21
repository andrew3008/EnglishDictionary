package space.br1440.platform.tracing.otel.javaagent.resource;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import space.br1440.platform.common.util.StringUtils;

final class BuildInfoReader {

    static final String BUILD_INFO_PATH = "META-INF/build-info.properties";
    static final String BUILD_INFO_PATH_BOOT_INF = "BOOT-INF/classes/META-INF/build-info.properties";
    static final String VERSION_KEY = "build.version";

    private final Function<String, InputStream> loader;

    BuildInfoReader() {
        this(BuildInfoReader::openFromTcclFirst);
    }

    BuildInfoReader(Function<String, InputStream> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Optional<String> readVersion() {
        Optional<String> root = readFrom(BUILD_INFO_PATH);
        if (root.isPresent()) {
            return root;
        }

        return readFrom(BUILD_INFO_PATH_BOOT_INF);
    }

    private Optional<String> readFrom(String path) {
        try (InputStream in = loader.apply(path)) {
            if (in == null) {
                return Optional.empty();
            }

            Properties props = new Properties();
            props.load(in);

            String version = props.getProperty(VERSION_KEY);
            if (StringUtils.hasText(version)) {
                return Optional.of(version.trim());
            }

            return Optional.empty();
        } catch (Exception | LinkageError failure) {
            return Optional.empty();
        }
    }

    private static InputStream openFromTcclFirst(String path) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            InputStream fromContext = contextLoader.getResourceAsStream(path);
            if (fromContext != null) {
                return fromContext;
            }
        }

        return BuildInfoReader.class.getClassLoader().getResourceAsStream(path);
    }
}
