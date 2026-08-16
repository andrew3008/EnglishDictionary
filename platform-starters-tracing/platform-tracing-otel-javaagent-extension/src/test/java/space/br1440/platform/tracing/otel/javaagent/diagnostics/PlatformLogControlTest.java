package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@link PlatformLogControl} (Фаза 14): порог детализации, регистронезависимый парсинг,
 * last-known-good при кривом вводе.
 */
class PlatformLogControlTest {

    @Test
    void print_threshold_by_severity() {
        PlatformLogControl control = new PlatformLogControl();
        control.setLevel(PlatformLogControl.LogLevel.WARN);

        assertThat(control.isEnabled(PlatformLogControl.LogLevel.ERROR)).isTrue();
        assertThat(control.isEnabled(PlatformLogControl.LogLevel.WARN)).isTrue();
        assertThat(control.isEnabled(PlatformLogControl.LogLevel.INFO)).isFalse();
        assertThat(control.isEnabled(PlatformLogControl.LogLevel.OFF)).isFalse();
    }

    @Test
    void parsing_is_case_insensitive() {
        PlatformLogControl control = new PlatformLogControl();
        assertThat(control.setLevel("debug")).isTrue();
        assertThat(control.getLevel()).isEqualTo(PlatformLogControl.LogLevel.DEBUG);
    }

    @Test
    void unknown_level_preserves_LKG() {
        PlatformLogControl control = new PlatformLogControl();
        control.setLevel(PlatformLogControl.LogLevel.INFO);

        assertThat(control.setLevel("verbose")).isFalse();
        assertThat(control.setLevel((String) null)).isFalse();
        assertThat(control.getLevel()).as("last-known-good сохранён").isEqualTo(PlatformLogControl.LogLevel.INFO);
    }
}
