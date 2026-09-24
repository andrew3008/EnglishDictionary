package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaskedSecretTest {

    @Test
    @DisplayName("present-секрет даёт непустой массив байт")
    void presentSecretCopiesNonEmptyBytes() {
        // given
        MaskedSecret secret = MaskedSecret.of("Jefe");
        MaskedSecret multibyte = MaskedSecret.of("я");

        // then
        assertThat(secret.isPresent()).isTrue();
        assertThat(secret.copyBytes())
                .isNotEmpty()
                .containsExactly("Jefe".getBytes(StandardCharsets.UTF_8));
        assertThat(multibyte.copyBytes()).isNotEmpty();
    }

    @Test
    @DisplayName("blank-строка остаётся ABSENT")
    void blankSecretIsAbsent() {
        // then
        assertThat(MaskedSecret.of(null).isPresent()).isFalse();
        assertThat(MaskedSecret.of("").isPresent()).isFalse();
        assertThat(MaskedSecret.of(" ").isPresent()).isFalse();
        assertThat(MaskedSecret.of(null).copyBytes()).isNull();
    }
}
