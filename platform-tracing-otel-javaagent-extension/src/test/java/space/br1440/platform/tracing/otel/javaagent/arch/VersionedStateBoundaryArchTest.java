package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * ArchUnit guardrails для {@code VersionedState} implementers и immutability holder-managed snapshots.
 */
@AnalyzeClasses(
        packages = {
                "space.br1440.platform.tracing.otel.javaagent",
                "space.br1440.platform.tracing.core.validation"
        },
        importOptions = ImportOption.DoNotIncludeTests.class
)
class VersionedStateBoundaryArchTest {

    @ArchTest
    static final ArchRule versionedStateImplsAllowlist =
            ModuleTaxonomyArchRules.VERSIONED_STATE_IMPLS_ALLOWLIST;

    @ArchTest
    static final ArchRule snapshotFieldsAreFinal =
            ModuleTaxonomyArchRules.SNAPSHOT_FIELDS_ARE_FINAL;

    @ArchTest
    static final ArchRule scrubbingSnapshotFieldsAreFinal =
            ModuleTaxonomyArchRules.SCRUBBING_SNAPSHOT_FIELDS_ARE_FINAL;

    @ArchTest
    static final ArchRule validationSnapshotFieldsAreFinal =
            ModuleTaxonomyArchRules.VALIDATION_SNAPSHOT_FIELDS_ARE_FINAL;

    @ArchTest
    static final ArchRule noApiConfigPackage = ModuleTaxonomyArchRules.NO_API_CONFIG_PACKAGE;
}
