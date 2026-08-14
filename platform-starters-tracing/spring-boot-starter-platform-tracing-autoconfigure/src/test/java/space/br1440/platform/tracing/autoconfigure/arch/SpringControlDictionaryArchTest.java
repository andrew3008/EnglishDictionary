package space.br1440.platform.tracing.autoconfigure.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;

import org.junit.jupiter.api.Test;

/**
 * Словарь Spring control-plane после выравнивания с Agent: запрет {@code Policy} и
 * канцелярских суффиксов. Слово {@code Client} на Spring разрешено (клиент протокола — Spring).
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure.control",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class SpringControlDictionaryArchTest {

    private static final String CONTROL_PACKAGE =
            "space.br1440.platform.tracing.autoconfigure.control";

    private static final List<String> FORBIDDEN_SUFFIXES = List.of(
            "Envelope", "View", "Projection", "Coordinator", "Access",
            "Holder", "Owner", "Manager", "Helper", "Facade");

    private static final List<String> FORBIDDEN_WORDS = List.of("Policy");

    @Test
    void noForbiddenSuffixesOrWordsInControlTypeNames() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(CONTROL_PACKAGE);

        for (JavaClass javaClass : imported) {
            if (javaClass.getName().contains("$")
                    || !javaClass.getPackageName().startsWith(CONTROL_PACKAGE)) {
                continue;
            }
            String simpleName = javaClass.getSimpleName();
            for (String suffix : FORBIDDEN_SUFFIXES) {
                assertThat(simpleName.endsWith(suffix))
                        .as("%s must not use the forbidden suffix '%s'", simpleName, suffix)
                        .isFalse();
            }
            for (String word : FORBIDDEN_WORDS) {
                assertThat(simpleName.contains(word))
                        .as("%s must not contain the forbidden word '%s'", simpleName, word)
                        .isFalse();
            }
        }
    }
}
