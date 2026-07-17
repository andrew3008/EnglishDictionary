package space.br1440.platform.tracing.api.control.protocol.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolViolationCode;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolVersion;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice-3 architectural guardrails:
 *  - TracingControlProtocol exposes ONLY: current(), version(), decode(Map).
 *  - No schema(), no validator(), no find(), no minSupportedVersion(), no maxSupportedVersion().
 *  - All production types in protocol package are from the approved whitelist only.
 *  - No legacy sub-packages (schema/, validation/, result/, version/) remain.
 *  - TracingControlProtocolViolation carries ViolationCode with exactly 6 codes.
 */
@DisplayName("Slice-3: API protocol public surface is minimal and stable")
class ApiProtocolNoImplementationDependencyArchTest {

    private static final String PROTOCOL_PACKAGE =
            "space.br1440.platform.tracing.api.control.protocol";

    /**
     * Approved public surface — these 7 types are the ONLY types allowed to be public
     * in the production protocol package (and its sub-packages).
     */
    private static final Set<String> PROTOCOL_PUBLIC_SURFACE = Set.of(
            "TracingControlProtocol",
            "TracingControlProtocolVersion",
            "TracingControlProtocolOperation",
            "TracingControlProtocolDecodeResult",
            "TracingControlProtocolViolation",
            "TracingControlProtocolViolationCode",
            "TracingControlProtocolKeys"
    );

    // ---------- entry-point surface assertions ----------

    @Test
    @DisplayName("TracingControlProtocol.current() returns non-null singleton")
    void currentReturnsSingleton() {
        TracingControlProtocol protocol = TracingControlProtocol.current();
        assertThat(protocol).isNotNull();
        assertThat(TracingControlProtocol.current()).isSameAs(protocol);
    }

    @Test
    @DisplayName("TracingControlProtocol exposes version() → major == 1")
    void versionIsMajorOne() {
        assertThat(TracingControlProtocol.current().version().major()).isEqualTo(1);
    }

    @Test
    @DisplayName("TracingControlProtocol.decode(null) returns failure result with MISSING_REQUIRED_KEY violations")
    void decodeNullIsFailure() {
        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(null);
        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).isNotEmpty();
    }

    @Test
    @DisplayName("TracingControlProtocol has NO schema() method")
    void noSchemaMethod() {
        assertThat(Arrays.stream(TracingControlProtocol.class.getMethods())
                .map(Method::getName)
                .noneMatch("schema"::equals))
                .as("schema() must not be public")
                .isTrue();
    }

    @Test
    @DisplayName("TracingControlProtocol has NO validator() method")
    void noValidatorMethod() {
        assertThat(Arrays.stream(TracingControlProtocol.class.getMethods())
                .map(Method::getName)
                .noneMatch("validator"::equals))
                .as("validator() must not be public")
                .isTrue();
    }

    @Test
    @DisplayName("TracingControlProtocol has NO find() method")
    void noFindMethod() {
        assertThat(Arrays.stream(TracingControlProtocol.class.getMethods())
                .map(Method::getName)
                .noneMatch("find"::equals))
                .as("find() must not be public")
                .isTrue();
    }

    @Test
    @DisplayName("TracingControlProtocol has NO minSupportedVersion()/maxSupportedVersion() methods")
    void noVersionRangeMethods() {
        Set<String> methodNames = Arrays.stream(TracingControlProtocol.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(methodNames).doesNotContain("minSupportedVersion", "maxSupportedVersion");
    }

    @Test
    @DisplayName("TracingControlProtocol public methods are exactly: current, version, decode")
    void exactPublicMethodSet() {
        Set<String> declared = Arrays.stream(TracingControlProtocol.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(declared).containsExactlyInAnyOrder("current", "version", "decode");
    }

    // ---------- package purity assertions ----------

    @Test
    @DisplayName("no public top-level types outside approved surface exist in protocol package")
    void onlyApprovedPublicTypes() {
        Set<String> actual = publicProductionSimpleNames();
        assertThat(actual)
                .as("unexpected public types in protocol package")
                .isSubsetOf(PROTOCOL_PUBLIC_SURFACE);
    }

    @Test
    @DisplayName("all approved public surface types are present")
    void allSurfaceTypesPresent() {
        Set<String> actual = publicProductionSimpleNames();
        assertThat(actual).containsAll(PROTOCOL_PUBLIC_SURFACE);
    }

    @Test
    @DisplayName("legacy sub-packages schema/, validation/, result/, version/ are absent")
    void noLegacySubPackages() {
        for (String subPkg : new String[]{"schema", "validation", "result", "version"}) {
            var classes = new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(PROTOCOL_PACKAGE + "." + subPkg);
            assertThat(classes)
                    .as("legacy sub-package %s must be empty", subPkg)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("no Registry, Command, Dto, Request, Bridge, LegacyV1 types in protocol package")
    void noForbiddenTypeNames() {
        Set<String> simpleNames = publicProductionSimpleNames();
        simpleNames.forEach(name ->
                assertThat(name).doesNotContainAnyWhitespacesOf("")
        );
        assertThat(simpleNames).noneMatch(n ->
                n.endsWith("Registry") || n.endsWith("Command") ||
                n.endsWith("Dto") || n.endsWith("Request") ||
                n.contains("Bridge") || n.contains("LegacyV1") ||
                n.equals("V1")
        );
    }

    @Test
    @DisplayName("legacy api.control.wire package is removed")
    void legacyWirePackageRemoved() {
        var legacy = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.tracing.api.control.wire");
        assertThat(legacy).isEmpty();
    }

    @Test
    @DisplayName("no public raw CURRENT_VERSION constants on TracingControlProtocol")
    void noPublicRawVersionConstants() {
        assertThat(Arrays.stream(TracingControlProtocol.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()))
                .map(Field::getName)
                .noneMatch(n -> n.contains("CURRENT_VERSION") || n.contains("CURRENT_VERSON")))
                .isTrue();
    }

    // ---------- violation model assertions ----------

    @Test
    @DisplayName("TracingControlProtocolViolation is a record with fields: key, reason, expectedType, actualType, code")
    void violationIsRecordWithExpectedComponents() {
        RecordComponent[] components = TracingControlProtocolViolation.class.getRecordComponents();
        assertThat(components).extracting(RecordComponent::getName)
                .containsExactly("key", "reason", "expectedType", "actualType", "code");
    }

    @Test
    @DisplayName("TracingControlProtocolViolationCode has exactly 6 codes")
    void violationCodeHasSixValues() {
        assertThat(TracingControlProtocolViolationCode.values()).hasSize(6);
    }

    // ---------- operations assertions ----------

    @Test
    @DisplayName("TracingControlProtocolOperation has exactly 3 values, no READ_SCHEMA")
    void operationEnumHasThreeValues() {
        TracingControlProtocolOperation[] ops = TracingControlProtocolOperation.values();
        assertThat(ops).hasSize(3);
        assertThat(Arrays.stream(ops).map(TracingControlProtocolOperation::name))
                .doesNotContain("READ_SCHEMA");
    }

    // ---------- decode result assertions ----------

    @Test
    @DisplayName("DecodeResult.success has valid=true, non-null operation and normalizedPayload")
    void decodeResultSuccessContract() {
        Map<String, Object> payload = Map.of(
                "contractVersion", 1,
                "operation", "READ_APPLIED_STATE"
        );
        TracingControlProtocolDecodeResult result =
                TracingControlProtocol.current().decode(payload);
        assertThat(result.valid()).isTrue();
        assertThat(result.operation()).isPresent();
        assertThat(result.normalizedPayload()).isNotNull();
        assertThat(result.violations()).isEmpty();
    }

    // ---------- helpers ----------

    private static Set<String> publicProductionSimpleNames() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PROTOCOL_PACKAGE)
                .stream()
                .filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(c -> !c.getName().contains("$"))
                .map(JavaClass::getSimpleName)
                .collect(Collectors.toSet());
    }
}
