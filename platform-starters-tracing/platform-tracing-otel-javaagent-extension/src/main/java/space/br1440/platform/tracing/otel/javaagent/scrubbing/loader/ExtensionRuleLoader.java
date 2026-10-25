package space.br1440.platform.tracing.otel.javaagent.scrubbing.loader;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

import space.br1440.platform.common.util.ArrayUtils;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ScrubbingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.StableScrubbingRule;

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

    public enum ValidationMode {
        WARNING,
        ERROR;

        public static ValidationMode parse(String raw) {
            if (StringUtils.isNullOrBlank(raw)) {
                return WARNING;
            }

            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "WARNING" -> WARNING;
                case "ERROR" -> ERROR;
                default -> throw new IllegalStateException("""
                        Invalid property 'platform.tracing.scrubbing.rules.validation-mode'='%s'. \
                        Accepted values: WARNING, ERROR\
                        """.formatted(raw)
                );
            };
        }
    }

    private final ValidationMode validationMode;
    private volatile URLClassLoader urlClassLoader;

    private final List<FailedEntry> failedEntries = new ArrayList<>();

    public ExtensionRuleLoader(ValidationMode validationMode) {
        this.validationMode = Objects.requireNonNull(validationMode, "validationMode");
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
                failOrWarn("<unknown>", FailedProviderReason.SERVICE_CONFIGURATION_ERROR, e.getClass().getName(), e);
                break;
            }

            Class<? extends SpanAttributeScrubbingRule> providerType;
            try {
                providerType = provider.type();
            } catch (ServiceConfigurationError | RuntimeException e) {
                PlatformThrowables.propagateFatalCauseChain(e);
                failOrWarn("<unknown>", FailedProviderReason.SERVICE_CONFIGURATION_ERROR, e.getClass().getName(), e);
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
                failOrWarn(providerName, FailedProviderReason.INSTANTIATION_ERROR, e.getClass().getName(), e);
                continue;
            }

            try {
                rules.add(StableScrubbingRule.captureExternal(rule));
            } catch (RuntimeException e) {
                PlatformThrowables.propagateFatalCauseChain(e);
                failOrWarn(providerName, FailedProviderReason.INVALID_METADATA, e.getClass().getName(), e);
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

        if (!jar.canRead()) {
            failOrWarn(jar.getName(), FailedProviderReason.UNREADABLE_FILE, "unreadable");
            return;
        }

        boolean clean;
        try {
            clean = isJarClean(jar);
        } catch (IOException e) {
            failOrWarn(jar.getName(), FailedProviderReason.UNREADABLE_FILE, "jar-inspection", e);
            return;
        }

        if (!clean) {
            failOrWarn(jar.getName(), FailedProviderReason.FORBIDDEN_CLASSES, "forbidden-classes");
            return;
        }

        try {
            urls.add(jar.toURI().toURL());
        } catch (IOException e) {
            failOrWarn(jar.getName(), FailedProviderReason.UNREADABLE_FILE, "url-conversion", e);
        }
    }

    private boolean isJarClean(File jar) throws IOException {
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
        }
    }

    private String canonicalize(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            failOrWarn(f.getName(), FailedProviderReason.UNREADABLE_FILE, "canonicalization", e);
            return null;
        }
    }

    private void failOrWarn(String name, FailedProviderReason reason, String safeDetails) {
        failOrWarn(name, reason, safeDetails, null);
    }

    private void failOrWarn(String name,
                            FailedProviderReason reason,
                            String safeDetails,
                            Throwable cause) {
        failedEntries.add(new FailedEntry(name, reason));
        if (validationMode == ValidationMode.ERROR) {
            throw new IllegalStateException(
                    "ERROR validation failed for '" + name + "': " + reason + " (" + safeDetails + ")",
                    cause
            );
        }

        log.log(ScrubbingLogEvent.EXTENSION_SCRUBBING_RULE_SKIPPED, fields -> {
            fields.putString(AgentLogField.NAME, name)
                    .putString(AgentLogField.REASON, reason.name())
                    .putString(AgentLogField.FAILURE_CONTEXT, safeDetails);
            if (cause != null) {
                fields.cause(cause);
            }
        });
    }

    public int getFailedProviders() {
        return failedEntries.size();
    }

    public List<FailedEntry> getFailedEntries() {
        return Collections.unmodifiableList(failedEntries);
    }

    public URLClassLoader releaseClassLoader() {
        URLClassLoader released = urlClassLoader;
        urlClassLoader = null;
        return released;
    }

    @Override
    public void close() {
        URLClassLoader ownedClassLoader = urlClassLoader;
        urlClassLoader = null;
        if (ownedClassLoader != null) {
            try {
                ownedClassLoader.close();
            } catch (IOException failure) {
                log.log(ScrubbingLogEvent.EXTENSION_CLASSLOADER_CLOSE_FAILED,
                        fields -> fields.cause(failure));
                throw new UncheckedIOException("Failed to close scrubbing extension classloader", failure);
            }
        }
    }

    public record FailedEntry(String name, FailedProviderReason reason) {
    }
}
