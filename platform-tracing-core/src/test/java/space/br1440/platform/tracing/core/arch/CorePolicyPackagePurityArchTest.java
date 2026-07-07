package space.br1440.platform.tracing.core.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * PR-1 forward-looking guardrail for pure policy packages in core.
 * <p>
 * {@code core.sampling} is active (PR-6B). {@code core.validation} is active (PR-9C).
 * Future {@code core.{scrubbing,enrichment}} packages are guarded the same way. Facade/span packages remain OTel-coupled (MIGRATION_RISK).
 * PR-9B adds {@code CORE_MAIN_NO_JMX}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CorePolicyPackagePurityArchTest {

    @ArchTest
    static final ArchRule corePolicyPackagesNoOtelOrSpring =
            ModuleTaxonomyArchRules.CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING;

    @ArchTest
    static final ArchRule coreMainNoJmx = ModuleTaxonomyArchRules.CORE_MAIN_NO_JMX;

    @ArchTest
    static final ArchRule samplingModelIsPure = ModuleTaxonomyArchRules.SAMPLING_MODEL_IS_PURE;

    @ArchTest
    static final ArchRule samplingPropertiesDependsOnlyOnModel =
            ModuleTaxonomyArchRules.SAMPLING_PROPERTIES_DEPENDS_ONLY_ON_MODEL;

    @ArchTest
    static final ArchRule samplingModelNotDependOnRuntimeState =
            ModuleTaxonomyArchRules.SAMPLING_MODEL_NOT_DEPEND_ON_RUNTIME_STATE;

    @ArchTest
    static final ArchRule samplingPolicyNoEngineOrConfig =
            ModuleTaxonomyArchRules.SAMPLING_POLICY_NO_ENGINE_OR_CONFIG;

    @ArchTest
    static final ArchRule productionChainAccessRestricted =
            ModuleTaxonomyArchRules.PRODUCTION_CHAIN_ACCESS_RESTRICTED;

    @ArchTest
    static final ArchRule samplingRuleImplsOnlyInPolicy =
            ModuleTaxonomyArchRules.SAMPLING_RULE_IMPLS_ONLY_IN_POLICY;
}
