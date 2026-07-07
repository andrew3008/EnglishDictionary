package space.br1440.platform.tracing.autoconfigure.sampling;

/**
 * Исключение, сигнализирующее о невозможности оперативно изменить head-sampling ratio
 * через JMX-мост: платформенное расширение OpenTelemetry Java Agent не подгружено в JVM,
 * либо MBean пропал из реестра, либо JMX-вызов завершился неисправимой ошибкой.
 * <p>
 * Это runtime-исключение клиентского слоя и сигнал о состоянии инфраструктуры, а не
 * валидационная ошибка: трактуется потребителем как «функция недоступна» (HTTP 503),
 * а не как «вход некорректен» (HTTP 400).
 */
public class SamplingControlUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SamplingControlUnavailableException(String message) {
        super(message);
    }

    public SamplingControlUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
