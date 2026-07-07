package space.br1440.platform.devtools.opusmcp.model;

import java.util.Set;

public final class ModelRegistry {

    public static final Set<String> ALLOWED_MODELS = Set.of(
            "claude-opus-4-8",
            "custom-opus-4-8");

    public boolean isAllowed(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        return ALLOWED_MODELS.contains(modelId.trim());
    }
}
