package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * PR-3 ArchUnit guardrail для MDC-границы {@code platform-tracing-otel-javaagent-extension}.
 * <p>
 * otel-extension грузится в {@code ExtensionClassLoader} без Spring-контекста;
 * MDC-операции идут через {@code otel.mdc.remote.RemoteServiceMdc}.
 * Из {@code api.mdc} разрешены только {@code TracingMdcKeys} и {@code RemoteServiceNameSource}.
 * Любой другой api.mdc-тип означает регресс к pre-PR-1 состоянию.
 *
 * @see ModuleTaxonomyArchRules#OTEL_EXTENSION_MDC_FROM_CORE
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ExtensionMdcBoundaryArchTest {

    /**
     * otel-extension MDC-зависимости: только {@code otel.mdc.remote.RemoteServiceMdc};
     * из {@code api.mdc} — только {@code TracingMdcKeys} и {@code RemoteServiceNameSource}.
     */
    @ArchTest
    static final ArchRule otelExtensionMdcFromCore =
            ModuleTaxonomyArchRules.OTEL_EXTENSION_MDC_FROM_CORE;
}
