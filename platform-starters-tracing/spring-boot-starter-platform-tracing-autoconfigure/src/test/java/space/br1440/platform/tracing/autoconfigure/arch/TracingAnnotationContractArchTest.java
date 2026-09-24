package space.br1440.platform.tracing.autoconfigure.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.TracingArchRules;

/**
 * In-repo wiring gate: правила annotation governance исполняются над production-пакетом
 * autoconfigure. При нуле {@code @Traced} в main это future-regression gate, а не доказательство
 * непустого enforcement; логика правил покрыта self-tests в {@code platform-tracing-testkit}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class TracingAnnotationContractArchTest {

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule no_traced_and_observed =
            TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule no_traced_on_async_or_reactive =
            TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule traced_attribute_type_allowlist =
            TracingArchRules.TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST;
}
