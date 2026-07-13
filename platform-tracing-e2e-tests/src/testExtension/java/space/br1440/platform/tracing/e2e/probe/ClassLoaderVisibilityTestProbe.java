package space.br1440.platform.tracing.e2e.probe;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Test-only OTel extension probe для верификации F1 (ADR-classloader-visibility-spike-finding).
 * <p>
 * Загружается через {@code otel.javaagent.extensions} в дочерней JVM E2E-теста.
 * Выполняется в {@code ExtensionClassLoader} агента — не в application {@code main()}.
 * <p>
 * Эмитирует машиночитаемые маркеры {@code CL_VISIBILITY:...} в {@code System.err}.
 * <p>
 * <b>F1</b>: нативный {@link ServiceLoader} в ExtensionClassLoader не видит custom-rules JAR,
 * переданный через {@code platform.tracing.scrubbing.rules.extensions}.
 * <p>
 * <b>URLClassLoader mechanism smoke</b> (не production F3): probe-side упрощённый
 * {@code URLClassLoader + ServiceLoader} по тому же property path. Не вызывает production
 * {@code ExtensionRuleLoader}. Production F3 proof — {@code CustomRuleSmokeE2ETest}.
 */
public final class ClassLoaderVisibilityTestProbe implements AutoConfigurationCustomizerProvider {

    /** Prefix всех маркеров этого probe. */
    public static final String LINE_PREFIX = "CL_VISIBILITY:";

    /** System-property для пути к custom-rules JAR. */
    static final String RULES_EXTENSIONS_PROPERTY = "platform.tracing.scrubbing.rules.extensions";

    /** Имя целевого кастомного E2E правила — ожидаемое значение {@link SpanAttributeScrubbingRule#name()}. */
    static final String TARGET_RULE_NAME = "custom-e2e-rule";

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addTracerProviderCustomizer((builder, config) -> {
            runProbe();
            return builder;
        });
    }

    private static void runProbe() {
        emit("BEGIN");
        emit("extensionProbeLoaded=true");
        emit("probeClassLoader=" + classLoaderName(ClassLoaderVisibilityTestProbe.class.getClassLoader()));

        // F1: нативный ServiceLoader не видит custom-rules JAR из otel.javaagent.extensions
        probeF1Variants();

        // Optional mechanism smoke: probe-side URLClassLoader (not production ExtensionRuleLoader)
        probeMechanismUrlClassLoader();

        emit("END");
    }

    // ---- F1: native ServiceLoader variants ----

    private static void probeF1Variants() {
        probeVariant("default", null);
        probeVariant("tccl", Thread.currentThread().getContextClassLoader());
        probeVariant("factory", ClassLoaderVisibilityTestProbe.class.getClassLoader());
        probeVariant("api", SpanAttributeScrubbingRule.class.getClassLoader());
    }

    private static void probeVariant(String variant, ClassLoader loader) {
        emit("variant=" + variant);
        ClassLoader effectiveLoader = loader == null
                ? Thread.currentThread().getContextClassLoader()
                : loader;
        emit("loader=" + classLoaderName(effectiveLoader));

        try {
            ServiceLoader<SpanAttributeScrubbingRule> serviceLoader = (loader == null)
                    ? ServiceLoader.load(SpanAttributeScrubbingRule.class)
                    : ServiceLoader.load(SpanAttributeScrubbingRule.class, loader);

            List<String> ruleNames = new ArrayList<>();
            boolean targetFound = false;
            Iterator<SpanAttributeScrubbingRule> iterator = serviceLoader.iterator();
            while (true) {
                try {
                    if (!iterator.hasNext()) {
                        break;
                    }
                    SpanAttributeScrubbingRule rule = iterator.next();
                    ruleNames.add(rule.name());
                    if (TARGET_RULE_NAME.equals(rule.name())) {
                        targetFound = true;
                    }
                } catch (ServiceConfigurationError e) {
                    emit("serviceConfigurationError=" + e.getClass().getName());
                    break;
                }
            }
            emit("foundRules=" + String.join(",", ruleNames));
            emit("targetFound=" + targetFound);
        } catch (Throwable t) {
            emit("probeError=" + t.getClass().getName());
        }
        emit("variantEnd=" + variant);
    }

    // ---- URLClassLoader mechanism smoke (not production ExtensionRuleLoader) ----

    private static void probeMechanismUrlClassLoader() {
        emit("mechanismBegin");
        String extensionsProperty = System.getProperty(RULES_EXTENSIONS_PROPERTY, "").trim();

        if (extensionsProperty.isEmpty()) {
            emit("mechanismLoadingMode=NONE");
            emit("mechanismCustomRules=0");
            emit("mechanismEnd");
            return;
        }

        emit("mechanismLoadingMode=PLATFORM_RULES_EXTENSIONS");

        String[] paths = extensionsProperty.split(",");
        List<URL> urls = new ArrayList<>();
        for (String path : paths) {
            File file = new File(path.trim());
            if (file.exists() && file.isFile()) {
                try {
                    urls.add(file.toURI().toURL());
                } catch (Exception e) {
                    emit("mechanismUrlError=" + e.getClass().getName());
                }
            } else {
                emit("mechanismMissingFile=" + path.trim());
            }
        }

        if (urls.isEmpty()) {
            emit("mechanismCustomRules=0");
            emit("mechanismEnd");
            return;
        }

        ClassLoader parent = ClassLoaderVisibilityTestProbe.class.getClassLoader();
        try (URLClassLoader urlCL = new URLClassLoader(urls.toArray(new URL[0]), parent)) {
            emit("mechanismUrlClassLoader=" + classLoaderName(urlCL));
            ServiceLoader<SpanAttributeScrubbingRule> sl = ServiceLoader.load(SpanAttributeScrubbingRule.class, urlCL);

            List<String> ruleNames = new ArrayList<>();
            Iterator<SpanAttributeScrubbingRule> iter = sl.iterator();
            while (iter.hasNext()) {
                try {
                    SpanAttributeScrubbingRule rule = iter.next();
                    ruleNames.add(rule.name());
                } catch (ServiceConfigurationError e) {
                    emit("mechanismServiceError=" + e.getClass().getName());
                    break;
                }
            }
            emit("mechanismFoundRules=" + String.join(",", ruleNames));
            emit("mechanismCustomRules=" + ruleNames.size());
        } catch (Throwable t) {
            emit("mechanismError=" + t.getClass().getName());
            emit("mechanismCustomRules=-1");
        }

        emit("mechanismEnd");
    }

    // ---- Utilities ----

    private static String classLoaderName(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void emit(String payload) {
        System.err.println(LINE_PREFIX + payload);
    }
}
