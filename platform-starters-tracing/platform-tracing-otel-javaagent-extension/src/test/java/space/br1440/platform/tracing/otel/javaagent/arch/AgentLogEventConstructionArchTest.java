package space.br1440.platform.tracing.otel.javaagent.arch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLogEventConstructionArchTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("space.br1440.platform.tracing.otel.javaagent");

    @Test
    void only_platform_agent_logger_constructs_agent_log_event() {
        // given
        ArchRule rule = noClasses()
                .that().areNotAssignableTo(PlatformAgentLogger.class)
                .should().callConstructor(
                        AgentLogEvent.class,
                        Instant.class,
                        AgentLogLevel.class,
                        String.class,
                        String.class,
                        String.class,
                        Map.class,
                        String.class)
                .because("only PlatformAgentLogger may construct AgentLogEvent in src/main");

        // when
        // then
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void constructor_is_package_private() throws NoSuchMethodException {
        // given
        Constructor<AgentLogEvent> constructor = AgentLogEvent.class.getDeclaredConstructor(
                Instant.class,
                AgentLogLevel.class,
                String.class,
                String.class,
                String.class,
                Map.class,
                String.class);

        // when
        int modifiers = constructor.getModifiers();

        // then
        assertThat(Modifier.isPublic(modifiers)).isFalse();
        assertThat(Modifier.isProtected(modifiers)).isFalse();
        assertThat(Modifier.isPrivate(modifiers)).isFalse();
        assertThat(AgentLogEvent.class).isFinal();
    }
}
