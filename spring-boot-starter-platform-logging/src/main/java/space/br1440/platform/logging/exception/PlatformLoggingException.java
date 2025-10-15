package space.br1440.platform.logging.exception;

class PlatformLoggingException extends RuntimeException {

    public PlatformLoggingException(String message) {
        super(message);
    }

    public PlatformLoggingException(String message, Throwable cause) {
        super(message, cause);
    }

}
