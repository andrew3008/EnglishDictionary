package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Anti-double-instrumentation guard (PR-7): outbound Kafka-классы НЕ создают span'ы и НЕ инжектят W3C.
 * <p>
 * Создание span'ов и W3C propagation — зона OTel Java Agent. Платформенные outbound-компоненты
 * добавляют только платформенные заголовки через OTel-free propagation-port.
 */
@DisplayName("ArchUnit: Kafka outbound не создаёт span'ы и не инжектит W3C")
class KafkaOutboundNoSpanArchTest {

    private static final JavaClasses OUTBOUND_CLASSES = new ClassFileImporter()
            .importPackages("space.br1440.platform.tracing.autoconfigure.kafka");

    @Test
    @DisplayName("PlatformKafka* outbound-классы не зависят от OpenTelemetry")
    void outboundDoesNotDependOnTraceApi() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameStartingWith("PlatformKafkaProducer")
                .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry..");
        rule.check(OUTBOUND_CLASSES);
    }

    @Test
    @DisplayName("outbound-классы не ссылаются на W3CTraceContextPropagator")
    void outboundDoesNotReferenceW3CPropagator() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameStartingWith("PlatformKafkaProducer")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator");
        rule.check(OUTBOUND_CLASSES);
    }
}
