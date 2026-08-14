package space.br1440.platform.tracing.autoconfigure.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.OtelDirectIntegrationRules;

/**
 * ArchUnit guardrails direct OTel integration для {@code platform-tracing-spring-boot-autoconfigure}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class OtelDirectIntegrationArchTest {

    @ArchTest
    static final ArchRule noLocalCopiesOfOtelSdkClasses =
            OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES;

    @ArchTest
    static final ArchRule noFakeOtelPackages =
            OtelDirectIntegrationRules.NO_FAKE_OTEL_PACKAGES;

    @ArchTest
    static final ArchRule noLocalOtelContractNames =
            OtelDirectIntegrationRules.NO_LOCAL_OTEL_CONTRACT_NAMES;

    @ArchTest
    static final ArchRule noRawRecordExceptionOutsideRecorder =
            OtelDirectIntegrationRules.NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER;
}
