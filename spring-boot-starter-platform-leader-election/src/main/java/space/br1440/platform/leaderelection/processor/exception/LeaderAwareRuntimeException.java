package space.br1440.platform.leaderelection.processor.exception;

public class LeaderAwareRuntimeException extends RuntimeException {

    public LeaderAwareRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

}
