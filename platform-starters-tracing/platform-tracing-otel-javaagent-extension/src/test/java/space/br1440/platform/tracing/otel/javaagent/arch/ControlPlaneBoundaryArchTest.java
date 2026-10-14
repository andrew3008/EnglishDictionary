package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
@SuppressWarnings("unused")
class ControlPlaneBoundaryArchTest {

    private static final String CONTROL = "space.br1440.platform.tracing.otel.javaagent.control";
    private static final String SETTINGS = CONTROL + ".settings";
    private static final String MODEL = SETTINGS + ".model";
    private static final String UPDATE = SETTINGS + ".update";
    private static final String CONTROL_JMX = CONTROL + ".jmx";
    private static final String LIFECYCLE = CONTROL_JMX + ".lifecycle";
    private static final String PROTOCOL = CONTROL_JMX + ".protocol";
    private static final String WRITER = PROTOCOL + ".PlatformSettingsMutationMBean";
    private static final Set<String> MUTATION_METHODS = Set.of(
            "apply",
            "rejectBeforeApply",
            "failBeforeApply"
    );

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule controlRootDoesNotDependOnJmx =
            noClasses()
                    .that().resideInAPackage(CONTROL)
                    .should().dependOnClassesThat().resideInAPackage(CONTROL_JMX + "..")
                    .because("the control root must remain independent from the JMX adapter");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule settingsModelIsPureData =
            noClasses()
                    .that().resideInAPackage(MODEL)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            SETTINGS,
                            UPDATE,
                            CONTROL_JMX + "..")
                    .because("the settings model must not depend on storage, update or transport layers");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule settingsDoesNotDependOnUpdate =
            noClasses()
                    .that().resideInAPackage(SETTINGS)
                    .should().dependOnClassesThat().resideInAPackage(UPDATE)
                    .because("update orchestration depends on settings storage, not the reverse");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule updateDoesNotDependOnJmx =
            noClasses()
                    .that().resideInAPackage(UPDATE)
                    .should().dependOnClassesThat().resideInAPackage(CONTROL_JMX + "..")
                    .because("settings updates must remain transport independent");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule onlyUpdaterCommitsSettings =
            noClasses()
                    .that().areNotAssignableTo(TracingSettingsUpdater.class)
                    .should().callMethod(
                            TracingSettingsStore.class,
                            "commit",
                            SettingsRevision.class,
                            TracingSettings.class,
                            SettingsSourceAuthority.class)
                    .because("TracingSettingsUpdater is the single settings mutation authority");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule writerRoutesTerminalFailuresThroughUpdater =
            classes()
                    .that().haveFullyQualifiedName(WRITER)
                    .should().callMethod(
                            TracingSettingsUpdater.class,
                            "rejectBeforeApply",
                            SettingsRejectionReason.class)
                    .andShould().callMethod(
                            TracingSettingsUpdater.class,
                            "failBeforeApply",
                            SettingsRejectionReason.class)
                    .because("the writer must route terminal failures through the update authority");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule protocolDoesNotDependOnLifecycle =
            noClasses()
                    .that().resideInAPackage(PROTOCOL)
                    .should().dependOnClassesThat().resideInAPackage(LIFECYCLE)
                    .because("the mutation protocol must not own MBeanServer lifecycle");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule writerIsTheOnlyJmxUpdaterCaller =
            noClasses()
                    .that().resideInAPackage(CONTROL_JMX + "..")
                    .and().doNotHaveFullyQualifiedName(WRITER)
                    .should().callMethodWhere(targetsTracingSettingsUpdater())
                    .because("PlatformSettingsMutationMBean is the only JMX mutation adapter");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule samplingJmxDoesNotDependOnScrubbingOrValidation =
            noClasses()
                    .that().resideInAPackage("..jmx.sampling..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..jmx.scrubbing..",
                            "..jmx.validation..")
                    .because("JMX domains must remain independent");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule scrubbingJmxDoesNotDependOnSampling =
            noClasses()
                    .that().resideInAPackage("..jmx.scrubbing..")
                    .should().dependOnClassesThat().resideInAPackage("..jmx.sampling..")
                    .because("JMX domains must remain independent");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule onlyRegistrationOwnersRegisterMBeans =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
                    .and().doNotHaveSimpleName("MBeanBatchRegistrar")
                    .should().callMethod(
                            "javax.management.MBeanServer",
                            "registerMBean",
                            "java.lang.Object",
                            "javax.management.ObjectName")
                    .because("only explicit registration owners may register MBeans");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule objectNamesAreCreatedOnlyByConstantsOwners =
            noClasses()
                    .that().haveSimpleNameNotEndingWith("ObjectNames")
                    .should().callConstructor("javax.management.ObjectName", "java.lang.String")
                    .because("raw ObjectName values belong to dedicated constants owners");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule traceHotPathsDoNotDependOnJmx =
            noClasses()
                    .that().resideInAnyPackage("..sampler..", "..processor..", "..exporter..")
                    .should().dependOnClassesThat().resideInAnyPackage("javax.management..", "..jmx..")
                    .because("trace hot paths must remain independent from JMX transport and publication");

    private static DescribedPredicate<JavaMethodCall> targetsTracingSettingsUpdater() {
        return new DescribedPredicate<>("target is a TracingSettingsUpdater mutation method") {
            @Override
            public boolean test(JavaMethodCall call) {
                return TracingSettingsUpdater.class.getName().equals(call.getTargetOwner().getName())
                        && MUTATION_METHODS.contains(call.getTarget().getName());
            }
        };
    }
}
