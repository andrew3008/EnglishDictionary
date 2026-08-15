package space.br1440.platform.tracing.otel.javaagent.resource;

import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

import space.br1440.platform.common.util.StringUtils;

public final class BuildInfoReader {

    static final String BUILD_INFO_PATH = "META-INF/build-info.properties";
    static final String BUILD_INFO_PATH_BOOT_INF = "BOOT-INF/classes/META-INF/build-info.properties";
    static final String VERSION_KEY = "build.version";

    private final ClasspathResourceLoader loader;

    public BuildInfoReader() {
        this(ClasspathResourceLoader.tcclFirst());
    }

    public BuildInfoReader(ClasspathResourceLoader loader) {
        this.loader = loader;
    }

    public Optional<String> readVersion() {
        Optional<String> root = readFrom(BUILD_INFO_PATH);
        if (root.isPresent()) {
            return root;
        }

        return readFrom(BUILD_INFO_PATH_BOOT_INF);
    }

    private Optional<String> readFrom(String path) {
        try (InputStream in = loader.open(path)) {
            if (in == null) {
                return Optional.empty();
            }

            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty(VERSION_KEY);
            if (!StringUtils.isNullOrBlank(version)) {
                return Optional.of(version.trim());
            }

            return Optional.empty();
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }
}
