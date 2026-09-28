package space.br1440.platform.tracing.otel.javaagent.logging.event;

import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class AgentLogEventArchitectureTest {

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule log_event_types_are_domain_enums = classes()
            .that().implement(AgentLogEventType.class)
            .should().beEnums()
            .andShould().haveSimpleNameEndingWith("LogEvent")
            .andShould().resideInAPackage(AgentLogEventType.class.getPackageName());

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule definitions_are_created_only_by_domain_enums = noClasses()
            .that().doNotImplement(AgentLogEventType.class)
            .should().callMethodWhere(target(declaredIn(AgentLogEventDefinition.class))
                    .and(target(nameMatching("noCause|requiresCause|optionalCause"))))
            .because("the agent log event catalog is closed; ad-hoc definitions bypass schema control");
}
