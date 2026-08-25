package space.br1440.platform.tracing.test.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import space.br1440.platform.tracing.api.synthetic.ApiBoundaryViolation;
import space.br1440.platform.tracing.api.synthetic.RequestIdentityContext;
import space.br1440.platform.tracing.autoconfigure.servlet.synthetic.WebBoundaryViolation;

class SliceIBoundaryArchRulesTest {

    @Test
    void apiRulesRejectImplementationAndOtelDependencies() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(ApiBoundaryViolation.class);

        // when
        // then
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.API_MAIN_NO_IMPLEMENTATION_DEPENDENCY.check(classes))
                .hasMessageContaining("TracingRuntime");
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES.check(classes))
                .hasMessageContaining("io.opentelemetry.context.Context");
    }

    @Test
    void webRulesRejectImplementationOtelContextAndInternalPropagationTypes() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(WebBoundaryViolation.class);

        // when
        // then
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY.check(classes))
                .hasMessageContaining("TracingRuntime");
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.WEB_MAIN_NO_OTEL_CONTEXT.check(classes))
                .hasMessageContaining("io.opentelemetry.context.Context");
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES.check(classes))
                .hasMessageContaining("OtelTraceparentReader");
    }

    @Test
    void identityRuleRejectsPublicInfrastructureType() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(RequestIdentityContext.class);

        // when
        // then
        assertThatThrownBy(() -> ModuleTaxonomyArchRules.IDENTITY_INTERNAL_TYPES_NOT_PUBLIC.check(classes))
                .hasMessageContaining("RequestIdentityContext");
    }
}
