package space.br1440.platform.tracing.otel.extension.scrubbing;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Загрузчик внешней конфигурации правил маскирования из Properties-файла.
 * <p>
 * Расположение файла задаётся свойством
 * {@link ExtensionPropertyNames#SCRUBBING_RULES_CONFIG}. Поддерживаемые префиксы:
 * <ul>
 *   <li>{@code classpath:tracing/scrubbing-rules.properties} — ресурс, доступный
 *       ClassLoader'у расширения OTel Java Agent;</li>
 *   <li>{@code file:/var/lib/app/scrubbing.properties} — путь в файловой системе;</li>
 *   <li>абсолютный или относительный путь без префикса (трактуется как файловый).</li>
 * </ul>
 * <p>
 * Формат файла — стандартный Java Properties; YAML-формат намеренно <b>не поддерживается</b>:
 * SnakeYAML недоступен в classloader'е расширения OTel Java Agent (нет shadow jar
 * в {@code platform-tracing-otel-extension/build.gradle}), а добавление SnakeYAML только ради
 * YAML-варианта (~200 KB shadow) не оправдано в v1. Properties-формат — нулевые внешние
 * зависимости.
 *
 * <h3>Поддерживаемые ключи</h3>
 * <ul>
 *   <li>{@code additional-built-in-rules=name1,name2,...} — список дополнительных встроенных
 *       правил (имена резолвятся через {@link BuiltInSpanAttributeScrubbingRules#resolve(String)});
 *       неизвестные имена логируются как WARN и пропускаются.</li>
 * </ul>
 * <p>
 * Любая ошибка чтения (IO, parse) логируется на WARN и приводит к возврату пустого списка —
 * сбой загрузчика не должен ломать pipeline, идущий поверх. Кастомные регулярные выражения
 * не поддерживаются в v1; для них используйте {@code ServiceLoader}-расширение интерфейса
 * {@code SpanAttributeScrubbingRule}.
 */
@Slf4j
public final class ScrubbingRulesLoader {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";
    private static final String KEY_ADDITIONAL_RULES = "additional-built-in-rules";

    private ScrubbingRulesLoader() {
        // utility-класс
    }

    /**
     * Загружает имена дополнительных правил из конфигурации. При отсутствии или ошибке возвращает
     * пустой список. Делегирует к {@link #load(String, ClassLoader)}.
     *
     * @param config источник свойств OTel SDK (агентский autoconfigure SPI)
     * @return имена дополнительных правил без дубликатов, в порядке появления в файле
     */
    public static List<String> load(ConfigProperties config) {
        String path = config.getString(ExtensionPropertyNames.SCRUBBING_RULES_CONFIG);
        return load(path, ScrubbingRulesLoader.class.getClassLoader());
    }

    /**
     * Загружает имена дополнительных правил из указанного пути с указанным ClassLoader.
     * Возвращает пустой список, если {@code path} равен {@code null} или пустой строке.
     * При ошибке чтения логирует WARN и возвращает пустой список — сбой не ломает pipeline.
     *
     * @param path          путь к Properties-файлу (поддерживаются префиксы {@code classpath:} и
     *                      {@code file:}); {@code null} или blank интерпретируется как «не задан»
     * @param classLoader   ClassLoader для разрешения classpath-ресурсов
     * @return имена дополнительных правил без дубликатов, в порядке появления в файле
     */
    public static List<String> load(String path, ClassLoader classLoader) {
        if (Strings.isBlank(path)) {
            return Collections.emptyList();
        }
        Properties properties;
        try (InputStream in = openStream(path, classLoader)) {
            if (in == null) {
                log.warn("Файл правил маскирования не найден: '{}'", path);
                return Collections.emptyList();
            }
            properties = new Properties();
            properties.load(in);
        } catch (IOException e) {
            log.warn("Не удалось прочитать файл правил маскирования '{}': {}", path, e.toString());
            return Collections.emptyList();
        }

        String raw = properties.getProperty(KEY_ADDITIONAL_RULES);
        if (Strings.isBlank(raw)) {
            return Collections.emptyList();
        }

        // Сохраняем порядок появления правил и удаляем дубликаты.
        Set<String> ordered = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static InputStream openStream(String path, ClassLoader classLoader) throws IOException {
        if (path.startsWith(CLASSPATH_PREFIX)) {
            String resource = path.substring(CLASSPATH_PREFIX.length());
            InputStream in = classLoader.getResourceAsStream(resource);
            return in == null ? null : new BufferedInputStream(in);
        }
        String filePath = path.startsWith(FILE_PREFIX) ? path.substring(FILE_PREFIX.length()) : path;
        Path p = Path.of(filePath);
        if (!Files.isReadable(p)) {
            return null;
        }
        return new BufferedInputStream(Files.newInputStream(p));
    }
}
