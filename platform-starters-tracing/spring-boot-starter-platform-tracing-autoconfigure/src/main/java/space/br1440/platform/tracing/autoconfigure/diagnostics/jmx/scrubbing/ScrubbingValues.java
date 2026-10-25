package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.scrubbing;

import java.util.Map;

public record ScrubbingValues(boolean enabled,
                              long policyVersion,
                              long rulesLoaded,
                              Map<String, Long> metrics) {
}
