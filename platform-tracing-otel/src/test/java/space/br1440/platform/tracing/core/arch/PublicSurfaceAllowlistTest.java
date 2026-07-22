package space.br1440.platform.tracing.core.arch;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSurfaceAllowlistTest {

    private static final String CORE_PACKAGE = "space.br1440.platform.tracing.core";
    private static final String ALLOWLIST_RESOURCE = "/abi/platform-tracing-otel-public-types.txt";

    @Test
    void PUBLIC_SURFACE_IS_EXACT() throws IOException {
        Set<String> actual = new TreeSet<>();
        new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(CORE_PACKAGE)
                .stream()
                .filter(type -> type.getModifiers().contains(JavaModifier.PUBLIC))
                .map(type -> type.getName())
                .filter(name -> !name.contains("$"))
                .forEach(actual::add);

        assertThat(actual).containsExactlyElementsOf(readAllowlist());
        assertThat(actual).noneMatch(name -> name.startsWith(CORE_PACKAGE + ".utils."));
    }

    private static Set<String> readAllowlist() throws IOException {
        try (InputStream input = PublicSurfaceAllowlistTest.class.getResourceAsStream(ALLOWLIST_RESOURCE)) {
            assertThat(input).as("Отсутствует allowlist публичной поверхности core").isNotNull();
            return new TreeSet<>(new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        }
    }
}
