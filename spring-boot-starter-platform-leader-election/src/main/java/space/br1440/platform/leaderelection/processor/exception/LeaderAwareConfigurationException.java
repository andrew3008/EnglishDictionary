package space.br1440.platform.leaderelection.processor.exception;

public class LeaderAwareConfigurationException extends RuntimeException {

    public LeaderAwareConfigurationException(String message) {
        super(message);
    }

    public LeaderAwareConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

}

