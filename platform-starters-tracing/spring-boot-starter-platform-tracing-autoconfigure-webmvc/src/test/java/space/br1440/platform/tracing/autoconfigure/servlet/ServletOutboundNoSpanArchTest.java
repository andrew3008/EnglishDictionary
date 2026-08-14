package space.br1440.platform.tracing.autoconfigure.servlet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Anti-double-instrumentation guard (PR-7): outbound HTTP-классы Servlet-стека НЕ создают span'ы
 * и НЕ инжектят W3C (это зона OTel Java Agent).
 */
@DisplayName("ArchUnit: Servlet HTTP outbound не создаёт span'ы и не инжектит W3C")
class ServletOutboundNoSpanArchTest {

    private static final JavaClasses OUTBOUND_CLASSES = new ClassFileImporter()
            .importPackages("space.br1440.platform.tracing.autoconfigure.servlet");

    @Test
    @DisplayName("PlatformOutbound* не зависят от OpenTelemetry")
    void outboundDoesNotDependOnTraceApi() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameStartingWith("PlatformOutbound")
                .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry..");
        rule.check(OUTBOUND_CLASSES);
    }
}
