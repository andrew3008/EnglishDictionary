package space.br1440.platform.tracing.autoconfigure.jmx;

/**
 * Выбрасывается {@link PlatformTracingJmxClient}, когда мутирующая JMX-операция завершилась
 * неудачей (домен недоступен, InstanceNotFoundException, и т.п.).
 */
public final class PlatformTracingJmxOperationException extends RuntimeException {

    public PlatformTracingJmxOperationException(String message) {
        super(message);
    }

    public PlatformTracingJmxOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
