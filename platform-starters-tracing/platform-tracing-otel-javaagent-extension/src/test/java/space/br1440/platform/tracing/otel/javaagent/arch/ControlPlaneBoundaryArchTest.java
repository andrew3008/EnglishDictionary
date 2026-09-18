package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.time.Instant;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
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
    static final ArchRule controlRootDoesNotDependOnJmx =
            noClasses()
                    .that().resideInAPackage(CONTROL)
                    .should().dependOnClassesThat().resideInAPackage(CONTROL_JMX + "..")
                    .because("the control root must remain independent from the JMX adapter");

    @ArchTest
    static final ArchRule settingsModelIsPureData =
            noClasses()
                    .that().resideInAPackage(MODEL)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            SETTINGS,
                            UPDATE,
                            CONTROL_JMX + "..")
                    .because("the settings model must not depend on storage, update or transport layers");

    @ArchTest
    static final ArchRule settingsDoesNotDependOnUpdate =
            noClasses()
                    .that().resideInAPackage(SETTINGS)
                    .should().dependOnClassesThat().resideInAPackage(UPDATE)
                    .because("update orchestration depends on settings storage, not the reverse");

    @ArchTest
    static final ArchRule updateDoesNotDependOnJmx =
            noClasses()
                    .that().resideInAPackage(UPDATE)
                    .should().dependOnClassesThat().resideInAPackage(CONTROL_JMX + "..")
                    .because("settings updates must remain transport independent");

    @ArchTest
    static final ArchRule onlyUpdaterCommitsSettings =
            noClasses()
                    .that().areNotAssignableTo(TracingSettingsUpdater.class)
                    .should().callMethod(
                            TracingSettingsStore.class,
                            "commit",
                            ExpectedSettingsRevision.class,
                            TracingSettings.class)
                    .because("TracingSettingsUpdater is the single settings mutation authority");

    @ArchTest
    static final ArchRule writerRoutesTerminalFailuresThroughUpdater =
            classes()
                    .that().haveFullyQualifiedName(WRITER)
                    .should().callMethod(
                            TracingSettingsUpdater.class,
                            "rejectBeforeApply",
                            String.class,
                            SettingsRejectionReason.class)
                    .andShould().callMethod(
                            TracingSettingsUpdater.class,
                            "failBeforeApply",
                            String.class,
                            SettingsRejectionReason.class)
                    .because("the writer must route terminal failures through the update authority");

    @ArchTest
    static final ArchRule protocolDoesNotDependOnLifecycle =
            noClasses()
                    .that().resideInAPackage(PROTOCOL)
                    .should().dependOnClassesThat().resideInAPackage(LIFECYCLE)
                    .because("the mutation protocol must not own MBeanServer lifecycle");

    @ArchTest
    static final ArchRule writerIsTheOnlyJmxUpdaterCaller =
            noClasses()
                    .that().resideInAPackage(CONTROL_JMX + "..")
                    .and().doNotHaveFullyQualifiedName(WRITER)
                    .should().callMethodWhere(targetsTracingSettingsUpdater())
                    .because("PlatformSettingsMutationMBean is the only JMX mutation adapter");

    @ArchTest
    static final ArchRule jmxDoesNotWriteDiagnosticsDirectly =
            noClasses()
                    .that().resideInAPackage(CONTROL_JMX + "..")
                    .should().callMethod(
                            LastSettingsUpdate.class,
                            "record",
                            SettingsUpdateAttempt.class,
                            SettingsUpdateResult.class)
                    .orShould().callMethod(
                            LastSettingsUpdate.class,
                            "recordPreApplyWithoutAttempt",
                            SettingsUpdateResult.class,
                            Instant.class)
                    .because("JMX adapters must not bypass update diagnostics ownership");

    @ArchTest
    static final ArchRule samplingJmxDoesNotDependOnScrubbingOrValidation =
            noClasses()
                    .that().resideInAPackage("..jmx.sampling..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..jmx.scrubbing..",
                            "..jmx.validation..")
                    .because("JMX domains must remain independent");

    @ArchTest
    static final ArchRule scrubbingJmxDoesNotDependOnSampling =
            noClasses()
                    .that().resideInAPackage("..jmx.scrubbing..")
                    .should().dependOnClassesThat().resideInAPackage("..jmx.sampling..")
                    .because("JMX domains must remain independent");

    @ArchTest
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
    static final ArchRule objectNamesAreCreatedOnlyByConstantsOwners =
            noClasses()
                    .that().haveSimpleNameNotEndingWith("ObjectNames")
                    .should().callConstructor("javax.management.ObjectName", "java.lang.String")
                    .because("raw ObjectName values belong to dedicated constants owners");

    @ArchTest
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
