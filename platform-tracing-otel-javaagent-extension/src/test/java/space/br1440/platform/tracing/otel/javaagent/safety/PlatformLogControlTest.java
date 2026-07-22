package space.br1440.platform.tracing.otel.javaagent.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@link PlatformLogControl} (Фаза 14): порог детализации, регистронезависимый парсинг,
 * last-known-good при кривом вводе.
 */
class PlatformLogControlTest {

    @Test
    void порог_печати_по_severity() {
        PlatformLogControl control = new PlatformLogControl();
        control.setLevel(PlatformLogControl.LogLevel.WARN);

        assertThat(control.isEnabled(PlatformLogControl.LogLevel.ERROR)).isTrue();
        assertThat(control.isEnabled(PlatformLogControl.LogLevel.WARN)).isTrue();
        assertThat(control.isEnabled(PlatformLogControl.LogLevel.INFO)).isFalse();
        assertThat(control.isEnabled(PlatformLogControl.LogLevel.OFF)).isFalse();
    }

    @Test
    void парсинг_регистронезависимый() {
        PlatformLogControl control = new PlatformLogControl();
        assertThat(control.setLevel("debug")).isTrue();
        assertThat(control.getLevel()).isEqualTo(PlatformLogControl.LogLevel.DEBUG);
    }

    @Test
    void неизвестный_уровень_сохраняет_LKG() {
        PlatformLogControl control = new PlatformLogControl();
        control.setLevel(PlatformLogControl.LogLevel.INFO);

        assertThat(control.setLevel("verbose")).isFalse();
        assertThat(control.setLevel((String) null)).isFalse();
        assertThat(control.getLevel()).as("last-known-good сохранён").isEqualTo(PlatformLogControl.LogLevel.INFO);
    }
}
