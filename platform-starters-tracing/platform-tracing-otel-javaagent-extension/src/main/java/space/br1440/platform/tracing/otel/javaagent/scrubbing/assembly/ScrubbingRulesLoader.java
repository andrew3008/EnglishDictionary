package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SCRUBBING_RULES_CONFIG;

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

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import lombok.experimental.UtilityClass;

/**
 * Загрузчик внешней конфигурации правил маскирования из Properties-файла.
 * <p>
 * Расположение файла задаётся свойством
 * {@code platform.tracing.scrubbing.rules-config}
 * ({@code ExtensionPropertyNames.SCRUBBING_RULES_CONFIG}). Поддерживаемые префиксы:
 * <ul>
 *   <li>{@code classpath:tracing/scrubbing-rules.properties} — ресурс,
 *       доступный ClassLoader'у расширения OTel Java Agent;</li>
 *   <li>{@code file:/var/lib/app/scrubbing.properties} — путь в файловой системе;</li>
 *   <li>абсолютный или относительный путь без префикса (трактуется как файловый).</li>
 * </ul>
 * <p>
 * Формат файла — стандартный Java Properties; YAML-формат намеренно <b>не поддерживается</b>:
 * SnakeYAML недоступен в classloader'е расширения OTel Java Agent, а добавление SnakeYAML только ради
 * YAML-варианта (~200 KB shadow) не оправдано.
 *
 * <h3>Поддерживаемые ключи</h3>
 * <ul>
 *   <li>{@code additional-built-in-rules=name1,name2,...} — список дополнительных встроенных
 *       правил; неизвестное имя приводит к отказу запуска.</li>
 * </ul>
 * <p>
 */
@UtilityClass
public final class ScrubbingRulesLoader {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ScrubbingRulesLoader.class);

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";
    private static final String KEY_ADDITIONAL_RULES = "additional-built-in-rules";

    public static List<String> load(String path, ClassLoader classLoader) {
        if (StringUtils.isNullOrBlank(path)) {
            return Collections.emptyList();
        }

        Properties properties;
        try (InputStream in = openStream(path, classLoader)) {
            if (in == null) {
                throw unreadable(path, null);
            }

            properties = new Properties();
            properties.load(in);
        } catch (SanitizerBootstrapException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw unreadable(path, e);
        }

        String raw = properties.getProperty(KEY_ADDITIONAL_RULES);
        if (StringUtils.isNullOrBlank(raw)) {
            return Collections.emptyList();
        }

        Set<String> ordered = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static SanitizerBootstrapException unreadable(String path, Throwable failure) {
        if (failure == null) {
            log.log(ScrubbingLogEvent.SCRUBBING_RULES_UNREADABLE,fields -> fields
                    .putString(AgentLogField.PATH, path));
        } else {
            log.log(ScrubbingLogEvent.SCRUBBING_RULES_UNREADABLE,fields -> fields
                    .putString(AgentLogField.PATH, path)
                    .cause(failure));
        }

        String message = "Property '" + SCRUBBING_RULES_CONFIG + "' points to an unreadable scrubbing rules resource: " + path;
        return new SanitizerBootstrapException(
                SanitizerBootstrapException.RULE_LOADING_FAILED,
                SCRUBBING_RULES_CONFIG,
                message,
                failure
        );
    }

    private static InputStream openStream(String path, ClassLoader classLoader) throws IOException {
        if (path.startsWith(CLASSPATH_PREFIX)) {
            String resource = path.substring(CLASSPATH_PREFIX.length());
            InputStream in = classLoader.getResourceAsStream(resource);
            return (in == null) ? null : new BufferedInputStream(in);
        }

        String filePath = path.startsWith(FILE_PREFIX) ? path.substring(FILE_PREFIX.length()) : path;
        Path p = Path.of(filePath);
        if (!Files.isReadable(p)) {
            return null;
        }

        return new BufferedInputStream(Files.newInputStream(p));
    }
}
