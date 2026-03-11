package space.br1440.platform.healthchecks.minio.exception;

public class SentinelObjectNotFoundException extends RuntimeException {

    public SentinelObjectNotFoundException(String message) {
        super(message);
    }

}
