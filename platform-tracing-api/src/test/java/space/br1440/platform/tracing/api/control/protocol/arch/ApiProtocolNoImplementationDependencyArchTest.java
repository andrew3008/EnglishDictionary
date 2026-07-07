package space.br1440.platform.tracing.api.control.protocol.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolValidator;
import space.br1440.platform.tracing.api.control.protocol.validation.TracingControlProtocolViolationCode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-4 supplemental: protocol public API surface and structural guardrails.
 */
@DisplayName("PR-4: API protocol package has no implementation coupling")
class ApiProtocolNoImplementationDependencyArchTest {

    private static final String PROTOCOL_PACKAGE = "space.br1440.platform.tracing.api.control.protocol";

    private static Set<JavaClass> importedProductionProtocolClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PROTOCOL_PACKAGE)
                .stream()
                .filter(ApiProtocolNoImplementationDependencyArchTest::isPublicTopLevelProductionType)
                .collect(Collectors.toSet());
    }

    private static boolean isPublicTopLevelProductionType(JavaClass javaClass) {
        return javaClass.getPackageName().startsWith(PROTOCOL_PACKAGE)
                && javaClass.getModifiers().contains(JavaModifier.PUBLIC)
                && !javaClass.getName().contains("$");
    }

    @Test
    @DisplayName("TracingControlProtocol is the single public entrypoint")
    void protocolEntryPoint() {
        assertThat(TracingControlProtocol.current().schema()).isNotNull();
        assertThat(TracingControlProtocol.current().validator())
                .isInstanceOf(TracingControlProtocolValidator.class);
        assertThat(TracingControlProtocolKeys.CONTRACT_VERSION).isEqualTo("contractVersion");
        assertThat(TracingControlProtocol.current().version().major()).isEqualTo(1);
    }

    @Test
    @DisplayName("TracingControlProtocolViolation carries TracingControlProtocolViolationCode")
    void violationHasCode() {
        RecordComponent[] components = TracingControlProtocolViolation.class.getRecordComponents();
        assertThat(components).extracting(RecordComponent::getName).contains("code");
        assertThat(TracingControlProtocolViolationCode.values()).hasSize(6);
    }

    @Test
    @DisplayName("no public raw version constants on protocol surface")
    void noPublicRawVersionConstants() throws Exception {
        assertThat(java.util.Arrays.stream(TracingControlProtocol.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .map(Field::getName)
                .noneMatch(name -> name.contains("CURRENT_VERSION") || name.contains("CURRENT_VERSON")))
                .isTrue();
    }

    @Test
    @DisplayName("no public top-level Registry in api.control.protocol")
    void protocolRegistryIsNotPublicTopLevelType() {
        assertThat(importedProductionProtocolClasses())
                .noneMatch(clazz -> clazz.getSimpleName().endsWith("Registry"));
    }

    @Test
    @DisplayName("no typed DTO boundary classes in api.control.protocol")
    void noTypedDtoBoundaryClasses() {
        assertThat(importedProductionProtocolClasses())
                .noneMatch(clazz -> clazz.getSimpleName().matches(".*(?:Command|Dto|Request)$"));
    }

    @Test
    @DisplayName("no public V1 schema/validator singletons in api.control.protocol")
    void noPublicV1Singletons() {
        assertThat(importedProductionProtocolClasses())
                .noneMatch(clazz -> "V1".equals(clazz.getSimpleName()));
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
    @DisplayName("no transitional legacy bridge types in api.control.protocol")
    void noLegacyBridgeTypes() {
        assertThat(importedProductionProtocolClasses())
                .noneMatch(clazz -> clazz.getSimpleName().contains("Bridge")
                        || clazz.getSimpleName().contains("LegacyV1"));
    }
}
