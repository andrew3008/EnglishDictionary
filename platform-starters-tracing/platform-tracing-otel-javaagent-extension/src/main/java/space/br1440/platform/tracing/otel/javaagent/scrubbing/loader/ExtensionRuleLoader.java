package space.br1440.platform.tracing.otel.javaagent.scrubbing.loader;

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

import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.StableScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.diagnostics.FailedProviderReason;
import space.br1440.platform.common.util.ArrayUtils;
import space.br1440.platform.common.util.StringUtils;

/**
 * Загрузчик внешних кастомных правил {@link SpanAttributeScrubbingRule} из свойства
 * {@code platform.tracing.scrubbing.rules.extensions} — comma-separated списка JAR-файлов или директорий.
 */
public final class ExtensionRuleLoader implements AutoCloseable {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ExtensionRuleLoader.class);

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

    public List<SpanAttributeScrubbingRule> load(String rawProperty) {
        if (StringUtils.isNullOrBlank(rawProperty)) {
            return List.of();
        }

        List<URL> urls = collectUrls(rawProperty);
        if (urls.isEmpty()) {
            return List.of();
        }

        urlClassLoader = new URLClassLoader(urls.toArray(URL[]::new), SpanAttributeScrubbingRule.class.getClassLoader());

        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        ServiceLoader<SpanAttributeScrubbingRule> loader = ServiceLoader.load(SpanAttributeScrubbingRule.class, urlClassLoader);

        Iterator<ServiceLoader.Provider<SpanAttributeScrubbingRule>> iterator = loader.stream().iterator();
        while (true) {
            ServiceLoader.Provider<SpanAttributeScrubbingRule> provider;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                provider = iterator.next();
            } catch (ServiceConfigurationError | RuntimeException e) {
                PlatformThrowables.propagateFatalCauseChain(e);
                failOrWarn("<unknown>", FailedProviderReason.SERVICE_CONFIGURATION_ERROR, e.getClass().getName());
                break;
            }

            Class<? extends SpanAttributeScrubbingRule> providerType;
            try {
                providerType = provider.type();
            } catch (ServiceConfigurationError | RuntimeException e) {
                PlatformThrowables.propagateFatalCauseChain(e);
                failOrWarn("<unknown>", FailedProviderReason.SERVICE_CONFIGURATION_ERROR, e.getClass().getName());
                continue;
            }

            if (providerType.getClassLoader() != urlClassLoader) {
                continue;
            }

            String providerName = providerType.getName();
            SpanAttributeScrubbingRule rule;
            try {
                rule = provider.get();
            } catch (ServiceConfigurationError | RuntimeException e) {
                PlatformThrowables.propagateFatalCauseChain(e);
                failOrWarn(providerName, FailedProviderReason.INSTANTIATION_ERROR, e.getClass().getName());
                continue;
            }

            try {
                rules.add(StableScrubbingRule.captureExternal(rule));
            } catch (RuntimeException e) {
                PlatformThrowables.propagateFatalCauseChain(e);
                failOrWarn(providerName, FailedProviderReason.INVALID_METADATA, e.getClass().getName());
            }
        }

        return Collections.unmodifiableList(rules);
    }

    private List<URL> collectUrls(String rawProperty) {
        List<URL> urls = new ArrayList<>();
        Set<String> seenCanonicalPaths = new HashSet<>();
        for (String token : rawProperty.split(",")) {
            String trimmed = token.trim();
            if (StringUtils.isNullOrEmpty(trimmed)) {
                continue;
            }

            File path = new File(trimmed);
            if (path.isDirectory()) {
                collectFromDirectory(path, urls, seenCanonicalPaths);
            } else if (path.isFile()) {
                tryAddJar(path, urls, seenCanonicalPaths);
            } else {
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
        if (ArrayUtils.isNullOrEmpty(jars)) {
            return;
        }

        Arrays.sort(jars, Comparator.comparing(File::getName)); // детерминированный порядок
        for (File jar : jars) {
            tryAddJar(jar, urls, seenCanonicalPaths);
        }
    }

    private void tryAddJar(File jar, List<URL> urls, Set<String> seenCanonicalPaths) {
        String canonical = canonicalize(jar);
        if (canonical == null) {
            return;
        }

        if (!seenCanonicalPaths.add(canonical)) {
            return;
        }

        if (otelExtensionPaths.contains(canonical)) {
            failOrWarn(jar.getName(), FailedProviderReason.DUPLICATE_CONFIG, "duplicate-config");
            return;
        }

        if (!jar.canRead()) {
            failOrWarn(jar.getName(), FailedProviderReason.UNREADABLE_FILE, "unreadable");
            return;
        }

        if (!isJarClean(jar)) {
            failOrWarn(jar.getName(), FailedProviderReason.FORBIDDEN_CLASSES, "forbidden-classes");
            return;
        }

        try {
            urls.add(jar.toURI().toURL());
        } catch (IOException e) {
            failOrWarn(jar.getName(), FailedProviderReason.UNREADABLE_FILE, "url-conversion");
        }
    }

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
            return false;
        }
    }

    private String canonicalize(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            failOrWarn(f.getName(), FailedProviderReason.UNREADABLE_FILE, "canonicalization");
            return null;
        }
    }

    private void failOrWarn(String name, FailedProviderReason reason, String safeDetails) {
        failedEntries.add(new FailedEntry(name, reason));
        if (validationMode == ValidationMode.STRICT) {
            throw new IllegalStateException("STRICT validation failed for '" + name + "': " + reason + " (" + safeDetails + ")");
        }

        log.warn(AgentEventCodes.SCRUB_EXTENSION_SKIPPED, "Extension scrubbing rule skipped",
                "name", name,
                "reason", String.valueOf(reason),
                "detail", safeDetails);
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
            } catch (IOException ignored) {
            }
        }
    }

    public record FailedEntry(String name, FailedProviderReason reason) {
    }
}
