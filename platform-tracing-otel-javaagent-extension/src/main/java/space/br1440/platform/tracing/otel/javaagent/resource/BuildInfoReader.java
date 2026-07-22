package space.br1440.platform.tracing.otel.javaagent.resource;

import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Читает {@code service.version} из {@code META-INF/build-info.properties} (ключ {@code build.version}).
 * <p>
 * Это <b>первичный production-источник</b> версии в Фазе 9 (version не задаётся в Helm/CI/config) —
 * совпадает с поведением Spring Boot OTel Starter ({@code BuildProperties}) и Agent
 * {@code SpringBootServiceVersionDetector}.
 *
 * <h2>Пути поиска</h2>
 * <ol>
 *   <li>{@code META-INF/build-info.properties} — корень classpath (fat JAR / Spring Boot repackage:
 *       файл лежит вне {@code BOOT-INF/classes/});</li>
 *   <li>{@code BOOT-INF/classes/META-INF/build-info.properties} — layered/exploded Docker-образы.</li>
 * </ol>
 * Поиск по каждому пути делегируется {@link ClasspathResourceLoader} (TCCL-first в production).
 * Любая ошибка чтения/парсинга → {@link Optional#empty()} без логов (резолвер сам решает, что делать).
 */
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

    /**
     * @return версия из {@code build.version}, либо {@link Optional#empty()} если файл/ключ
     *         отсутствуют или произошла ошибка
     */
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
            if (Strings.isNotBlank(version)) {
                return Optional.of(version.trim());
            }
            return Optional.empty();
        } catch (Exception | LinkageError e) {
            // Любая ошибка чтения — version просто не определён из этого источника.
            return Optional.empty();
        }
    }
}
