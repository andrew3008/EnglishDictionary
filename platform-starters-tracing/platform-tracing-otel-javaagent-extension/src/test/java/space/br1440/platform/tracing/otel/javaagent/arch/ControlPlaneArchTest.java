package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

/**
 * Архитектурные гейты пакетной топологии
 * {@code control}/{@code control.settings}/{@code control.settings.model}/
 * {@code control.settings.update}/{@code control.settings.validation}/{@code control.jmx},
 * единственного авторитета изменения и независимости корня от JMX. Самоподдерживающийся
 * allowlist: новый top-level тип вне списка валит сборку до внесения в этот класс.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent.control",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ControlPlaneArchTest {

    private static final String ROOT_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent.control";
    private static final String SETTINGS_PACKAGE = ROOT_PACKAGE + ".settings";
    private static final String SETTINGS_MODEL_PACKAGE = SETTINGS_PACKAGE + ".model";
    private static final String SETTINGS_UPDATE_PACKAGE = SETTINGS_PACKAGE + ".update";
    private static final String SETTINGS_VALIDATION_PACKAGE = SETTINGS_PACKAGE + ".validation";
    private static final String JMX_PACKAGE = ROOT_PACKAGE + ".jmx";
    private static final String JMX_LIFECYCLE_PACKAGE = JMX_PACKAGE + ".lifecycle";
    private static final String JMX_PROTOCOL_PACKAGE = JMX_PACKAGE + ".protocol";
    private static final String JMX_WRITER = JMX_PROTOCOL_PACKAGE + ".PrivateControlWriterMBean";

    private static final Set<String> ROOT_ALLOWLIST = Set.of(
            "RuntimeInstanceIds",
            "TracingSettingsRevision",
            "TracingSettingsSource");

    private static final Set<String> SETTINGS_ALLOWLIST = Set.of(
            "SettingsCommitOutcome",
            "SettingsCommitResult",
            "TracingSettingsDigest",
            "TracingSettingsStore");

    private static final Set<String> SETTINGS_MODEL_ALLOWLIST = Set.of(
            "ExpectedSettingsRevision",
            "SettingsSourceAuthority",
            "SettingsUpdateTrigger",
            "SettingsValidationConstraints",
            "TracingLogLevel",
            "TracingSettings");

    private static final Set<String> SETTINGS_UPDATE_ALLOWLIST = Set.of(
            "LastSettingsUpdate",
            "LastSettingsUpdateSnapshot",
            "SettingsRejectionReason",
            "SettingsUpdateAttempt",
            "SettingsUpdateResult",
            "SettingsUpdateStatus",
            "TracingSettingsUpdater",
            "TracingSettingsValidator");

    private static final Set<String> SETTINGS_VALIDATION_ALLOWLIST = Set.of(
            "PlatformEnvironment",
            "ValidationMode",
            "ValidationModeResolver");

    private static final Set<String> JMX_ALLOWLIST = Set.of();

    private static final Set<String> JMX_LIFECYCLE_ALLOWLIST = Set.of(
            "PlatformSettingsStatusMBean",
            "PlatformSettingsStatusRegistration",
            "PrivateControlMarkerMBean",
            "PrivateControlPlane",
            "PrivateControlServer");

    private static final Set<String> JMX_PROTOCOL_ALLOWLIST = Set.of(
            "DecodedSettingsUpdate",
            "PrivateControlDecodeException",
            "PrivateControlObjectNames",
            "PrivateControlOpenTypeCodec",
            "PrivateControlWireTypes",
            "PrivateControlWriterMBean");

    private static final List<String> FORBIDDEN_SUFFIXES = List.of(
            "Envelope", "View", "Projection", "Coordinator", "Access",
            "Holder", "Owner", "Manager", "Helper", "Facade");

    private static final List<String> FORBIDDEN_WORDS = List.of("Policy", "Client");

    @ArchTest
    static final ArchRule no_control_state_package =
            noClasses()
                    .should().resideInAPackage("..control.state..")
                    .because("срез S7 удалил control.state целиком; пакет не должен возвращаться");

    @ArchTest
    static final ArchRule only_eight_control_packages =
            classes()
                    .that().resideInAPackage("..javaagent.control..")
                    .should().resideInAnyPackage(
                            ROOT_PACKAGE,
                            SETTINGS_PACKAGE,
                            SETTINGS_MODEL_PACKAGE,
                            SETTINGS_UPDATE_PACKAGE,
                            SETTINGS_VALIDATION_PACKAGE,
                            JMX_PACKAGE,
                            JMX_LIFECYCLE_PACKAGE,
                            JMX_PROTOCOL_PACKAGE)
                    .because("под ..javaagent.control.. разрешены только control, control.settings, "
                            + "control.settings.model, control.settings.update, "
                            + "control.settings.validation (S3: SP-02 guard, единый владелец для "
                            + "startup и runtime) и три control.jmx пакета");

    @ArchTest
    static final ArchRule root_does_not_depend_on_jmx =
            noClasses()
                    .that().resideInAPackage(ROOT_PACKAGE)
                    .should().dependOnClassesThat().resideInAPackage(JMX_PACKAGE + "..")
                    .because("активация JMX вынесена в PrivateControlPlane.activate(source); "
                            + "корень control не должен снова обрастать зависимостью на control.jmx..");

    @ArchTest
    static final ArchRule model_is_pure_data =
            noClasses()
                    .that().resideInAPackage(SETTINGS_MODEL_PACKAGE)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            SETTINGS_PACKAGE,
                            SETTINGS_UPDATE_PACKAGE,
                            JMX_PACKAGE + "..")
                    .because("control.settings.model — чистые данные; зависимость только "
                            + "settings/update/jmx -> model, не наоборот "
                            + "(RuntimeInstanceIds из корня — утилита формата идентификатора)");

    @ArchTest
    static final ArchRule settings_does_not_depend_on_update =
            noClasses()
                    .that().resideInAPackage(SETTINGS_PACKAGE)
                    .should().dependOnClassesThat().resideInAPackage(SETTINGS_UPDATE_PACKAGE)
                    .because("control.settings — CAS-примитив и digest; оркестрация мутации "
                            + "(update) зависит от settings, не наоборот");

    @ArchTest
    static final ArchRule update_does_not_depend_on_jmx =
            noClasses()
                    .that().resideInAPackage(SETTINGS_UPDATE_PACKAGE)
                    .should().dependOnClassesThat().resideInAPackage(JMX_PACKAGE + "..")
                    .because("оркестрация мутации независима от JMX wire-адаптера");

    @ArchTest
    static final ArchRule only_updater_commits_to_store =
            noClasses()
                    .that().areNotAssignableTo(TracingSettingsUpdater.class)
                    .should().callMethod(TracingSettingsStore.class, "commit",
                            ExpectedSettingsRevision.class,
                            TracingSettings.class)
                    .because("единственный авторитет изменения: TracingSettingsStore.commit "
                            + "вызывается только из TracingSettingsUpdater — ни facade, ни JMX writer "
                            + "не обходят валидацию и диагностику updater'а");

    @ArchTest
    static final ArchRule status_mbean_is_read_only =
            noClasses()
                    .that().haveSimpleName("PlatformSettingsStatusMBean")
                    .should().dependOnClassesThat().haveSimpleName("TracingSettingsUpdater")
                    .because("PlatformSettingsStatusMBean живёт на platform MBeanServer только для "
                            + "чтения; изменение появляется исключительно на приватном control plane");

    @ArchTest
    static final ArchRule jmx_writer_routes_terminal_failures_through_updater =
            classes()
                    .that().haveFullyQualifiedName(JMX_WRITER)
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
                    .because("decode/internal terminal failures на Writer.invoke пишут diagnostics "
                            + "только через TracingSettingsUpdater.rejectBeforeApply/failBeforeApply");

    @ArchTest
    static final ArchRule protocol_does_not_depend_on_lifecycle =
            noClasses()
                    .that().resideInAPackage(JMX_PROTOCOL_PACKAGE)
                    .should().dependOnClassesThat().resideInAPackage(JMX_LIFECYCLE_PACKAGE)
                    .because("private-control protocol не владеет MBeanServer lifecycle; "
                            + "разрешено только направление lifecycle -> protocol");

    @ArchTest
    static final ArchRule writer_is_the_only_jmx_updater_caller =
            noClasses()
                    .that().resideInAPackage(JMX_PACKAGE + "..")
                    .and().doNotHaveFullyQualifiedName(JMX_WRITER)
                    .should().callMethodWhere(targetsTracingSettingsUpdater())
                    .because("PrivateControlWriterMBean — единственный JMX-адаптер, "
                            + "который вызывает TracingSettingsUpdater");

    @ArchTest
    static final ArchRule jmx_does_not_write_diagnostics_directly =
            noClasses()
                    .that().resideInAPackage(JMX_PACKAGE + "..")
                    .should().callMethod(
                            LastSettingsUpdate.class,
                            "record",
                            SettingsUpdateAttempt.class,
                            SettingsUpdateResult.class)
                    .because("control.jmx не обходит updater: LastSettingsUpdate.record вызывается "
                            + "только из control.settings.update");

    @ArchTest
    static final ArchRule jmx_does_not_write_pre_apply_diagnostics_directly =
            noClasses()
                    .that().resideInAPackage(JMX_PACKAGE + "..")
                    .should().callMethod(
                            LastSettingsUpdate.class,
                            "recordPreApplyWithoutAttempt",
                            SettingsUpdateResult.class,
                            Instant.class)
                    .because("null-attempt pre-apply path принадлежит TracingSettingsUpdater, "
                            + "а не Writer/codec");

    @Test
    void jmxSchemaSupportingTypesArePackagePrivate() throws ClassNotFoundException {
        for (String className : List.of(
                JMX_LIFECYCLE_PACKAGE + ".PlatformSettingsStatusMBean",
                JMX_LIFECYCLE_PACKAGE + ".PlatformSettingsStatusRegistration",
                JMX_LIFECYCLE_PACKAGE + ".PrivateControlMarkerMBean",
                JMX_LIFECYCLE_PACKAGE + ".PrivateControlServer",
                JMX_PROTOCOL_PACKAGE + ".DecodedSettingsUpdate",
                JMX_PROTOCOL_PACKAGE + ".PrivateControlDecodeException",
                JMX_PROTOCOL_PACKAGE + ".PrivateControlOpenTypeCodec",
                JMX_PROTOCOL_PACKAGE + ".PrivateControlWireTypes")) {
            Class<?> type = Class.forName(className);
            assertThat(Modifier.isPublic(type.getModifiers()))
                    .as("%s must not be a public API type", className)
                    .isFalse();
            assertThat(Modifier.isPrivate(type.getModifiers()))
                    .as("%s must remain package-visible for same-package wire/parity tests", className)
                    .isFalse();
        }
    }

    /**
     * G-PCP-10: ровно три public production-типа в трёх control.jmx пакетах —
     * {@code PrivateControlPlane}, {@code PrivateControlWriterMBean}, {@code PrivateControlObjectNames}.
     */
    @Test
    void exactlyThreePublicJmxTypesAreExplicit() throws ClassNotFoundException {
        for (String className : List.of(
                JMX_LIFECYCLE_PACKAGE + ".PrivateControlPlane",
                JMX_PROTOCOL_PACKAGE + ".PrivateControlObjectNames",
                JMX_WRITER)) {
            assertThat(Modifier.isPublic(Class.forName(className).getModifiers()))
                    .as("%s must be public in the S2/S3 topology", className)
                    .isTrue();
        }
    }

    @Test
    void rootPackageMatchesAllowlist() {
        assertTopLevelTypesMatch(ROOT_PACKAGE, ROOT_ALLOWLIST);
    }

    @Test
    void settingsPackageMatchesAllowlist() {
        assertTopLevelTypesMatch(SETTINGS_PACKAGE, SETTINGS_ALLOWLIST);
    }

    @Test
    void settingsModelPackageMatchesAllowlist() {
        assertTopLevelTypesMatch(SETTINGS_MODEL_PACKAGE, SETTINGS_MODEL_ALLOWLIST);
    }

    @Test
    void settingsUpdatePackageMatchesAllowlist() {
        assertTopLevelTypesMatch(SETTINGS_UPDATE_PACKAGE, SETTINGS_UPDATE_ALLOWLIST);
    }

    @Test
    void settingsValidationPackageMatchesAllowlist() {
        assertTopLevelTypesMatch(SETTINGS_VALIDATION_PACKAGE, SETTINGS_VALIDATION_ALLOWLIST);
    }

    @Test
    void jmxPackageMatchesAllowlist() {
        assertTopLevelTypesMatch(JMX_PACKAGE, JMX_ALLOWLIST);
    }

    @Test
    void jmxLifecyclePackageMatchesAllowlist() {
        assertTopLevelTypesMatch(JMX_LIFECYCLE_PACKAGE, JMX_LIFECYCLE_ALLOWLIST);
    }

    @Test
    void jmxProtocolPackageMatchesAllowlist() {
        assertTopLevelTypesMatch(JMX_PROTOCOL_PACKAGE, JMX_PROTOCOL_ALLOWLIST);
    }

    @Test
    void noForbiddenSuffixesOrWordsInControlTypeNames() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT_PACKAGE);

        for (JavaClass javaClass : imported) {
            if (isNested(javaClass) || !javaClass.getPackageName().startsWith(ROOT_PACKAGE)) {
                continue;
            }
            String simpleName = javaClass.getSimpleName();
            for (String suffix : FORBIDDEN_SUFFIXES) {
                assertThat(simpleName.endsWith(suffix))
                        .as("%s must not use the forbidden suffix '%s'", simpleName, suffix)
                        .isFalse();
            }
            for (String word : FORBIDDEN_WORDS) {
                assertThat(simpleName.contains(word))
                        .as("%s must not contain the forbidden word '%s'", simpleName, word)
                        .isFalse();
            }
        }
    }

    private static void assertTopLevelTypesMatch(String packageName, Set<String> allowlist) {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(packageName);

        Set<String> actual = new HashSet<>();
        for (JavaClass javaClass : imported) {
            if (isNested(javaClass) || !javaClass.getPackageName().equals(packageName)) {
                continue;
            }
            actual.add(javaClass.getSimpleName());
        }

        assertThat(actual)
                .as("top-level types in %s must exactly match the allowlist; "
                        + "add new intentional types to ControlPlaneArchTest", packageName)
                .isEqualTo(allowlist);
    }

    private static boolean isNested(JavaClass javaClass) {
        return javaClass.getName().contains("$");
    }

    private static DescribedPredicate<JavaMethodCall> targetsTracingSettingsUpdater() {
        return new DescribedPredicate<>("target owner is TracingSettingsUpdater") {
            @Override
            public boolean test(JavaMethodCall call) {
                return TracingSettingsUpdater.class.getName().equals(call.getTargetOwner().getName());
            }
        };
    }
}
