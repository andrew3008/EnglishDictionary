package space.br1440.platform.tracing.jmx.contract.tool;

public final class ContractValidationException extends IllegalArgumentException {

    private final String code;

    public ContractValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
