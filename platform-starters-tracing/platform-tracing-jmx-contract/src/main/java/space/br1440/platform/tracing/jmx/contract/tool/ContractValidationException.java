package space.br1440.platform.tracing.jmx.contract.tool;

final class ContractValidationException extends IllegalArgumentException {

    private final String code;

    ContractValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
