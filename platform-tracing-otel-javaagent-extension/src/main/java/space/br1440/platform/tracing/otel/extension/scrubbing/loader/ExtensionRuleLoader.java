package space.br1440.platform.tracing.otel.extension.scrubbing.loader;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.diagnostics.FailedProviderReason;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Загрузчик внешних кастомных правил {@link SpanAttributeScrubbingRule} из свойства
 * {@code platform.tracing.scrubbing.rules.extensions} — comma-separated списка JAR-файлов или
 * директорий.
 * <p>
 * Это <b>третий</b> источник правил (помимо built-in enum и bundled-SPI через ClassLoader
 * платформы). Spike подтвердил (ADR-classloader-visibility-spike-finding), что нативный
 * {@code ServiceLoader} не видит sibling JAR из {@code otel.javaagent.extensions}, поэтому
 * кастомные правила грузятся через выделенный {@link URLClassLoader} с parent = ClassLoader API.
 *
 * <h3>JAR hygiene</h3>
 * JAR с кастомными правилами не должен бандлить platform-tracing-api или OTel-классы. Такие JAR
 * отбраковываются (см. {@link #FORBIDDEN_PREFIXES}); проверяются именно {@code *.class}-записи,
 * чтобы не давать ложных срабатываний на {@code META-INF/services}, Maven-POM и Javadoc.
 *
 * <h3>Режим валидации</h3>
 * {@link ValidationMode#LENIENT} (prod-default) — WARN + пропуск проблемного JAR/провайдера,
 * приложение стартует. {@link ValidationMode#STRICT} (CI/strict-профиль) — fail-fast при
 * дублировании JAR, нарушении hygiene, нечитаемом JAR или битом провайдере.
 *
 * <h3>Жизненный цикл (важно)</h3>
 * {@link URLClassLoader} удерживается живым на весь срок JVM (без hot-reload в v1).
 * <b>Нельзя</b> вызывать {@link #close()}, пока загруженные правила активны: правило может
 * lazy-load helper-классы из своего JAR, а закрытый loader их уже не отдаст. {@link #close()}
 * допустим только при остановке агента/SDK (если есть соответствующий lifecycle hook); при
 * отсутствии hook'а loader живёт до конца JVM. <b>Запрещён</b> {@code try-with-resources}
 * вокруг {@link #load(String)}.
 */
@Slf4j
public final class ExtensionRuleLoader implements AutoCloseable {

    /** Запрещённые префиксы classpath внутри кастомных JAR (проверяются только для {@code *.class}). */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "space/br1440/platform/tracing/api/",
            "io/opentelemetry/"
    );

    /**
     * Режим валидации кастомных правил.
     * <ul>
     *   <li>{@link #LENIENT} — WARN + skip проблемного JAR/провайдера (prod-default);</li>
     *   <li>{@link #STRICT} — fail-fast (CI/strict-профиль).</li>
     * </ul>
     */
    public enum ValidationMode { LENIENT, STRICT }

    private final ValidationMode validationMode;
    private final Set<String> otelExtensionPaths;
    private volatile URLClassLoader urlClassLoader;

    private final List<FailedEntry> failedEntries = new ArrayList<>();

    public ExtensionRuleLoader(ValidationMode validationMode, Set<String> otelExtensionPaths) {
        this.validationMode = Objects.requireNonNull(validationMode, "validationMode");
        this.otelExtensionPaths = Objects.requireNonNull(otelExtensionPaths, "otelExtensionPaths");
    }

    /**
     * Загружает кастомные правила из значения свойства.
     * <p>
     * Пустое/{@code null}/состоящее только из запятых значение — ноль правил, без WARN.
     *
     * @param rawProperty значение {@code platform.tracing.scrubbing.rules.extensions}
     * @return неизменяемый список загруженных экземпляров правил
     */
    public List<SpanAttributeScrubbingRule> load(String rawProperty) {
        if (Strings.isBlank(rawProperty)) {
            log.debug("[scrubbing] platform.tracing.scrubbing.rules.extensions не задано — кастомные правила не грузятся");
            return List.of();
        }

        List<URL> urls = collectUrls(rawProperty);
        if (urls.isEmpty()) {
            return List.of();
        }

        // parent = ClassLoader API, чтобы кастомные правила видели SpanAttributeScrubbingRule из платформы.
        // Все отвалидированные JAR собираются в один URLClassLoader (поддержка helper-JAR рядом).
        urlClassLoader = new URLClassLoader(urls.toArray(URL[]::new), SpanAttributeScrubbingRule.class.getClassLoader());

        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        ServiceLoader<SpanAttributeScrubbingRule> loader = ServiceLoader.load(SpanAttributeScrubbingRule.class, urlClassLoader);

        // Резолв провайдеров ленивый: ошибка может прилететь уже на hasNext()/next() (битый
        // META-INF/services, отсутствующий/несовместимый класс). Перехватываем ограниченным
        // multi-catch на обеих фазах, чтобы битый JAR не валил старт агента. Stack trace и
        // exception message НЕ логируем — кастомное правило могло положить туда секрет.
        Iterator<ServiceLoader.Provider<SpanAttributeScrubbingRule>> iterator = loader.stream().iterator();
        while (true) {
            ServiceLoader.Provider<SpanAttributeScrubbingRule> provider;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                provider = iterator.next();
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                failOrWarn("<unknown>", FailedProviderReason.SERVICE_CONFIGURATION_ERROR, e.getClass().getName());
                // Дальнейший обход того же итератора после ошибки ненадёжен — прекращаем.
                break;
            }

            // Берём только провайдеров, определённых самим URLClassLoader'ом из сконфигурированных
            // JAR. Провайдеры, видимые через parent (bundled SPI платформы), грузятся отдельным
            // источником в фабрике — иначе было бы двойное считывание/дедупликация.
            if (provider.type().getClassLoader() != urlClassLoader) {
                continue;
            }

            String providerName = "<unknown>";
            try {
                providerName = provider.type().getName();
                rules.add(provider.get());
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                failOrWarn(providerName, FailedProviderReason.INSTANTIATION_ERROR, e.getClass().getName());
            }
        }
        return Collections.unmodifiableList(rules);
    }

    private List<URL> collectUrls(String rawProperty) {
        List<URL> urls = new ArrayList<>();
        // Канонические пути уже добавленных JAR: защита от дублей file+directory и symlink'ов.
        Set<String> seenCanonicalPaths = new HashSet<>();
        // Согласно документации OTel свойство-список — comma-separated (не File.pathSeparator).
        for (String token : rawProperty.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            File path = new File(trimmed);
            if (path.isDirectory()) {
                collectFromDirectory(path, urls, seenCanonicalPaths);
            } else if (path.isFile()) {
                tryAddJar(path, urls, seenCanonicalPaths);
            } else {
                log.warn("[scrubbing] Путь не существует: '{}'. Кастомные правила отсюда не загружены.", trimmed);
                failOrWarn(new File(trimmed).getName(), FailedProviderReason.MISSING_PATH, "missing-path");
            }
        }
        return urls;
    }

    private void collectFromDirectory(File dir, List<URL> urls, Set<String> seenCanonicalPaths) {
        File[] jars = dir.listFiles(f ->
                !f.isHidden()
                        && !f.getName().startsWith(".")
                        && !f.getName().endsWith(".tmp")
                        && f.getName().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            log.debug("[scrubbing] В директории {} нет JAR-файлов.", dir);
            return;
        }
        Arrays.sort(jars, Comparator.comparing(File::getName)); // детерминированный порядок
        for (File jar : jars) {
            tryAddJar(jar, urls, seenCanonicalPaths);
        }
    }

    private void tryAddJar(File jar, List<URL> urls, Set<String> seenCanonicalPaths) {
        String canonical = canonicalize(jar);

        // Дедуп внутри конфигурации: тот же JAR, пришедший через file+directory или symlink.
        if (!seenCanonicalPaths.add(canonical)) {
            log.debug("[scrubbing] JAR '{}' уже добавлен (canonical-дубликат) — пропускаем.", jar.getName());
            return;
        }

        if (otelExtensionPaths.contains(canonical)) {
            log.warn("[scrubbing] JAR '{}' указан и в otel.javaagent.extensions — это запрещено.", jar.getName());
            failOrWarn(jar.getName(), FailedProviderReason.DUPLICATE_CONFIG, "duplicate-config");
            return;
        }
        if (!jar.canRead()) {
            log.warn("[scrubbing] JAR '{}' не читается. Пропускаем.", jar.getName());
            failOrWarn(jar.getName(), FailedProviderReason.UNREADABLE_FILE, "unreadable");
            return;
        }
        if (!isJarClean(jar)) {
            log.warn("[scrubbing] JAR '{}' содержит запрещённые platform/OTel-классы и не должен бандлить "
                    + "platform-tracing-api или io.opentelemetry. Отбраковываем.", jar.getName());
            failOrWarn(jar.getName(), FailedProviderReason.FORBIDDEN_CLASSES, "forbidden-classes");
            return;
        }
        try {
            urls.add(jar.toURI().toURL());
        } catch (IOException e) {
            log.warn("[scrubbing] Не удалось преобразовать путь JAR в URL: {}", jar.getName());
            failOrWarn(jar.getName(), FailedProviderReason.UNREADABLE_FILE, "url-conversion");
        }
    }

    /**
     * Лёгкая проверка hygiene: обходит записи JAR без загрузки классов. Запрещён только
     * {@code *.class} с запрещённым префиксом — META-INF/services, POM и Javadoc не триггерят.
     */
    private boolean isJarClean(File jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            return jf.stream().noneMatch(entry -> {
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    return false;
                }
                for (String prefix : FORBIDDEN_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        return true;
                    }
                }
                return false;
            });
        } catch (IOException e) {
            log.warn("[scrubbing] Не удалось прочитать записи JAR для hygiene-проверки: {}", jar.getName(), e);
            return false; // не доверяем нечитаемому JAR
        }
    }

    private String canonicalize(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    /**
     * Единый безопасный путь обработки сбоя загрузки правила/JAR.
     * <p>
     * В {@link ValidationMode#STRICT} — fail-fast (без {@code safeDetails} и путей в сообщении
     * исключения). В {@link ValidationMode#LENIENT} — WARN + skip. Stack trace и
     * {@code Throwable.getMessage()} <b>никогда</b> не логируются: кастомное правило могло
     * положить туда секрет. {@code safeDetails} обязан быть санитизированным (имя файла/провайдера,
     * класс ошибки), но не полный canonical path.
     */
    private void failOrWarn(String name, FailedProviderReason reason, String safeDetails) {
        failedEntries.add(new FailedEntry(name, reason));
        if (validationMode == ValidationMode.STRICT) {
            throw new IllegalStateException("STRICT validation failed: " + reason);
        }
        log.warn("[scrubbing] Кастомное правило пропущено. reason={}, details={}", reason, safeDetails);
    }

    public int getFailedProviders() {
        return failedEntries.size();
    }

    public List<FailedEntry> getFailedEntries() {
        return Collections.unmodifiableList(failedEntries);
    }

    @Override
    public void close() {
        if (urlClassLoader != null) {
            try {
                urlClassLoader.close();
            } catch (IOException e) {
                log.warn("[scrubbing] Не удалось закрыть URLClassLoader кастомных правил", e);
            }
        }
    }

    /** Запись о неуспешно загруженном провайдере (для диагностики). */
    public record FailedEntry(String name, FailedProviderReason reason) {
    }
}
