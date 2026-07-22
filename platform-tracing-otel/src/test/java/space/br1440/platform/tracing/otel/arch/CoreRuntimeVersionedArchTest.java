package space.br1440.platform.tracing.otel.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * ArchUnit guardrails для {@code otel.runtime.versioned}: location of CAS primitive and allowlist.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CoreRuntimeVersionedArchTest {

    @ArchTest
    static final ArchRule versionedStatePrimitiveOnlyInCore =
            ModuleTaxonomyArchRules.VERSIONED_STATE_PRIMITIVE_ONLY_IN_CORE;

    @ArchTest
    static final ArchRule versionedStateImplsAllowlist =
            ModuleTaxonomyArchRules.VERSIONED_STATE_IMPLS_ALLOWLIST;

    @ArchTest
    static final ArchRule validationSnapshotFieldsAreFinal =
            ModuleTaxonomyArchRules.VALIDATION_SNAPSHOT_FIELDS_ARE_FINAL;

    @ArchTest
    static final ArchRule noApiRuntimeStatePackage =
            ModuleTaxonomyArchRules.NO_API_RUNTIME_STATE_PACKAGE;
}
