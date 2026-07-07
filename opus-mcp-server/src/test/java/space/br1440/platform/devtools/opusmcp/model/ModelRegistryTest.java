package space.br1440.platform.devtools.opusmcp.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryTest {

    private final ModelRegistry registry = new ModelRegistry();

    @Test
    void allowsConfiguredModels() {
        assertThat(registry.isAllowed("claude-opus-4-8")).isTrue();
        assertThat(registry.isAllowed("custom-opus-4-8")).isTrue();
    }

    @Test
    void rejectsUnknownModel() {
        assertThat(registry.isAllowed("gpt-999")).isFalse();
        assertThat(registry.isAllowed(null)).isFalse();
        assertThat(registry.isAllowed("")).isFalse();
    }
}
