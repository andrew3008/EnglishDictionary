package space.br1440.platform.tracing.autoconfigure.servlet;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Anti-double-instrumentation guard (PR-7): outbound HTTP-классы Servlet-стека НЕ создают span'ы
 * и НЕ инжектят W3C (это зона OTel Java Agent).
 */
@DisplayName("ArchUnit: Servlet HTTP outbound не создаёт span'ы и не инжектит W3C")
class ServletOutboundNoSpanArchTest {

    private static final JavaClasses OUTBOUND_CLASSES = new ClassFileImporter()
            .importPackages("space.br1440.platform.tracing.autoconfigure.servlet");

    @Test
    @DisplayName("PlatformOutbound* / PlatformHttpRequestSetter не зависят от io.opentelemetry.api.trace")
    void outboundDoesNotDependOnTraceApi() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameStartingWith("PlatformOutbound")
                .or().haveSimpleName("PlatformHttpRequestSetter")
                .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry.api.trace..");
        rule.check(OUTBOUND_CLASSES);
    }
}
