package space.br1440.platform.tracing.autoconfigure.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurationToMillisTest {

    @Test
    void toOtelString_конвертирует_миллисекунды_корректно() {
        // Базовый кейс: 100 мс — типичное значение otel.bsp.export.timeout.
        assertThat(DurationToMillis.toOtelString(Duration.ofMillis(100))).isEqualTo("100");
    }

    @Test
    void toOtelString_конвертирует_секунды_в_миллисекунды() {
        // 30 секунд = 30000 мс — стандартный таймаут экспорта OTel по умолчанию.
        assertThat(DurationToMillis.toOtelString(Duration.ofSeconds(30))).isEqualTo("30000");
    }

    @Test
    void toOtelString_не_теряет_точность_на_наносекундных_остатках() {
        // Duration.toMillis() округляет вниз; контракт OTel SPEC — integer-ms, поэтому
        // явно фиксируем поведение «округлять к нижней границе» для предсказуемости.
        Duration d = Duration.ofMillis(500).plusNanos(700_000); // 500 мс + 0.7 мс
        assertThat(DurationToMillis.toOtelString(d)).isEqualTo("500");
    }

    @Test
    void toOtelString_допускает_нулевую_длительность() {
        // Ноль = «отключить таймаут / поставить минимальное значение». OTel SPEC допускает 0.
        assertThat(DurationToMillis.toOtelString(Duration.ZERO)).isEqualTo("0");
    }

    @Test
    void toOtelString_бросает_исключение_на_null() {
        assertThatThrownBy(() -> DurationToMillis.toOtelString(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void toOtelString_бросает_исключение_на_отрицательной_длительности() {
        // OTel SPEC: «If a negative value is provided, the SDK MUST generate a warning,
        // gracefully ignore the setting and use the default». Платформа выбрала более жёсткое
        // поведение (fail-fast на стороне source-of-truth Spring Properties), чтобы конфиг
        // не сериализовывал заведомо невалидные значения в OTEL_*.
        assertThatThrownBy(() -> DurationToMillis.toOtelString(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }
}
