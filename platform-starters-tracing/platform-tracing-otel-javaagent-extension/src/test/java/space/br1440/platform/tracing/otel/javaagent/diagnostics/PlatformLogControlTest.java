package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт {@link PlatformLogControl} (Фаза 14): порог детализации, регистронезависимый парсинг,
 * last-known-good при кривом вводе.
 */
class PlatformLogControlTest {

    @Test
    void off_is_a_valid_configured_level() {
        PlatformLogControl control = new PlatformLogControl();
        control.setLevel(AgentLogLevel.OFF);

        assertThat(control.getLevel()).isEqualTo(AgentLogLevel.OFF);
    }

    @Test
    void parsing_is_case_insensitive() {
        PlatformLogControl control = new PlatformLogControl();
        assertThat(control.setLevel("debug")).isTrue();
        assertThat(control.getLevel()).isEqualTo(AgentLogLevel.DEBUG);
    }

    @Test
    void unknown_level_preserves_LKG() {
        PlatformLogControl control = new PlatformLogControl();
        control.setLevel(AgentLogLevel.INFO);

        assertThat(control.setLevel("verbose")).isFalse();
        assertThat(control.setLevel((String) null)).isFalse();
        assertThat(control.getLevel()).as("last-known-good сохранён").isEqualTo(AgentLogLevel.INFO);
    }
}
