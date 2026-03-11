package space.br1440.platform.healthchecks.kafka.exception;

public class ConsumerGroupNotFoundException extends RuntimeException {

    public ConsumerGroupNotFoundException(String message) {
        super(message);
    }
}
