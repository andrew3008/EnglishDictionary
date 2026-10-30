package space.br1440.platform.tracing.otel.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import io.opentelemetry.context.propagation.TextMapGetter;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class TextMapGetterKeysArchTest {

    private static final String CARRIER_GETTER =
            "space.br1440.platform.tracing.otel.propagation.OtelTraceparentReaderImpl$CarrierGetter";

    private static final DescribedPredicate<JavaClass> IMPLEMENT_TEXT_MAP_GETTER =
            JavaClass.Predicates.implement(TextMapGetter.class);

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("space.br1440.platform.tracing.otel");

    @ArchTest
    static final ArchRule textMapGettersDoNotDeclareKeys = classes()
            .that(IMPLEMENT_TEXT_MAP_GETTER)
            .should(notDeclareKeys())
            .because("platform propagators read known fields through get and getAll");

    @Test
    void rule_rejects_text_map_getter_that_declares_keys() {
        // given
        JavaClasses fixture = new ClassFileImporter().importClasses(KeysDeclaringTextMapGetter.class);

        // when
        EvaluationResult result = textMapGettersDoNotDeclareKeys.evaluate(fixture);

        // then
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("declares TextMapGetter.keys"));
    }

    @Test
    void rule_checks_carrier_getter() {
        // given
        JavaClass carrier = PRODUCTION_CLASSES.get(CARRIER_GETTER);

        // when
        boolean selected = IMPLEMENT_TEXT_MAP_GETTER.test(carrier);
        EvaluationResult result = textMapGettersDoNotDeclareKeys.evaluate(PRODUCTION_CLASSES);

        // then
        assertThat(selected).isTrue();
        assertThat(carrier.getMethods()).noneMatch(method -> method.getName().equals("keys"));
        assertThat(result.hasViolation()).isFalse();
    }

    private static ArchCondition<JavaClass> notDeclareKeys() {
        return new ArchCondition<>("not declare keys") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean declaresKeys = javaClass.getMethods().stream()
                        .anyMatch(method -> method.getName().equals("keys") && method.getOwner().equals(javaClass));
                if (declaresKeys) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName() + " declares TextMapGetter.keys"
                    ));
                }
            }
        };
    }

    static final class KeysDeclaringTextMapGetter implements TextMapGetter<Map<String, String>> {

        @Override
        @SuppressWarnings("deprecation")
        public Iterable<String> keys(Map<String, String> carrier) {
            return List.of();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return null;
        }
    }
}
