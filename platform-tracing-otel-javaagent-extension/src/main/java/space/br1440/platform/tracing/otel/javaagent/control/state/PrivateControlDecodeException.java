package space.br1440.platform.tracing.otel.javaagent.control.state;

final class PrivateControlDecodeException extends RuntimeException {

    private final PolicyMessageCode messageCode;

    PrivateControlDecodeException(PolicyMessageCode messageCode, String message) {
        super(message);
        this.messageCode = messageCode;
    }

    PolicyMessageCode messageCode() {
        return messageCode;
    }
}
