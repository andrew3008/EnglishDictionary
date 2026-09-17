package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation;

public record ValidationValues(boolean enabled,
                               boolean strict,
                               long violationsTotal,
                               long configVersion,
                               String configLastUpdatedSource) {
}
