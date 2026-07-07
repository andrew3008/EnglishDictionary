package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Self-test для {@link OtelSdkArchRules}: правила сами должны корректно ловить запрещённые вызовы.
 */
class OtelSdkArchRulesTest {

    @Test
    void NO_BUILD_AND_REGISTER_GLOBAL_ловит_прямой_вызов() {
        JavaClasses classes = new ClassFileImporter().importClasses(BuildAndRegisterGlobalViolator.class);

        assertThatThrownBy(() -> OtelSdkArchRules.NO_BUILD_AND_REGISTER_GLOBAL.check(classes))
                .hasMessageContaining("buildAndRegisterGlobal");
    }

    @Test
    void NO_GLOBAL_OPEN_TELEMETRY_ловит_GlobalOpenTelemetry_get() {
        JavaClasses classes = new ClassFileImporter().importClasses(GlobalGetViolator.class);

        assertThatThrownBy(() -> OtelSdkArchRules.NO_GLOBAL_OPEN_TELEMETRY.check(classes))
                .hasMessageContaining("GlobalOpenTelemetry");
    }

    @Test
    void правила_не_срабатывают_на_корректном_коде() {
        JavaClasses classes = new ClassFileImporter().importClasses(CorrectUsage.class);

        OtelSdkArchRules.NO_BUILD_AND_REGISTER_GLOBAL.check(classes);
        OtelSdkArchRules.NO_GLOBAL_OPEN_TELEMETRY.check(classes);

        assertThat(classes).isNotEmpty();
    }

    /** Намеренный нарушитель: вызывает {@code buildAndRegisterGlobal()}. */
    @SuppressWarnings("unused")
    static class BuildAndRegisterGlobalViolator {
        void wrong() {
            OpenTelemetrySdk.builder().buildAndRegisterGlobal();
        }
    }

    /** Намеренный нарушитель: вызывает {@link GlobalOpenTelemetry#get()}. */
    @SuppressWarnings("unused")
    static class GlobalGetViolator {
        void wrong() {
            GlobalOpenTelemetry.get();
        }
    }

    /** Корректный класс: только локальный SDK без глобальной регистрации. */
    @SuppressWarnings("unused")
    static class CorrectUsage {
        void ok() {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().build();
            sdk.close();
        }
    }
}
