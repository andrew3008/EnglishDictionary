package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation;

public record ValidationValues(String validationLevel,
                               long violationsTotal,
                               long configVersion,
                               String configLastUpdatedSource) {
}
