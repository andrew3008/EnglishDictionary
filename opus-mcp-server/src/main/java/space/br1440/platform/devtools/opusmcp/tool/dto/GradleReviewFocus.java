package space.br1440.platform.devtools.opusmcp.tool.dto;

import java.util.Arrays;
import java.util.Optional;

/** Review focus for {@code review_gradle_build_with_opus}. */
public enum GradleReviewFocus {
    DEPENDENCY_MANAGEMENT("dependency_management"),
    PLUGIN_CONFIGURATION("plugin_configuration"),
    CONFIGURATION_CACHE("configuration_cache"),
    TASK_GRAPH("task_graph"),
    MULTI_MODULE_GOVERNANCE("multi_module_governance"),
    TEST_SETUP("test_setup"),
    PUBLISHING("publishing"),
    PERFORMANCE("performance"),
    SECURITY("security"),
    ALL("all");

    private final String wireValue;

    GradleReviewFocus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<GradleReviewFocus> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(v -> v.wireValue.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
