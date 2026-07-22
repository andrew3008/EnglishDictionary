package space.br1440.platform.tracing.test.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.span.synthetic.SpanFactoryReaderViolation;
import space.br1440.platform.tracing.api.synthetic.ApiBoundaryViolation;
import space.br1440.platform.tracing.api.synthetic.RequestIdentityContext;
import space.br1440.platform.tracing.autoconfigure.servlet.synthetic.WebBoundaryViolation;

class SliceIBoundaryArchRulesTest {

    @Test
    void apiRulesRejectImplementationAndOtelDependencies() {
        JavaClasses classes = new ClassFileImporter().importClasses(ApiBoundaryViolation.class);

        assertThatThrownBy(() -> ModuleTaxonomyArchRules.API_MAIN_NO_IMPLEMENTATION_DEPENDENCY.check(classes))
                .hasMessageContaining("TracingRuntime");
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES.check(classes))
                .hasMessageContaining("io.opentelemetry.context.Context");
    }

    @Test
    void webRulesRejectImplementationOtelContextAndInternalPropagationTypes() {
        JavaClasses classes = new ClassFileImporter().importClasses(WebBoundaryViolation.class);

        assertThatThrownBy(() -> ModuleTaxonomyArchRules.WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY.check(classes))
                .hasMessageContaining("TracingRuntime");
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.WEB_MAIN_NO_OTEL_CONTEXT.check(classes))
                .hasMessageContaining("io.opentelemetry.context.Context");
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES.check(classes))
                .hasMessageContaining("TraceControlHeaderInjector");
    }

    @Test
    void spanFactoryRuleRejectsImplementationReaderDependency() {
        JavaClasses classes = new ClassFileImporter().importClasses(SpanFactoryReaderViolation.class);

        assertThatThrownBy(() -> ModuleTaxonomyArchRules.SPAN_FACTORY_API_NO_TRACEPARENT_READER.check(classes))
                .hasMessageContaining("OtelTraceparentReader");
    }

    @Test
    void identityRuleRejectsPublicInfrastructureType() {
        JavaClasses classes = new ClassFileImporter().importClasses(RequestIdentityContext.class);

        assertThatThrownBy(() -> ModuleTaxonomyArchRules.IDENTITY_INTERNAL_TYPES_NOT_PUBLIC.check(classes))
                .hasMessageContaining("RequestIdentityContext");
    }
}
