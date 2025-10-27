package space.br1440.platform.leaderelection.lock.persistence.redis.exception;

public class RedisDBException extends RuntimeException {

    public RedisDBException(String message, Throwable cause) {
        super(message, cause);
    }

}
