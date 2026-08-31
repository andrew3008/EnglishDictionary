package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

public final class JmxMBeanInfoShapeViolationException extends RuntimeException {

    public static final String FAILURE_CODE = "JMX_MBEAN_INFO_SHAPE_VIOLATION";

    public JmxMBeanInfoShapeViolationException() {
        super(FAILURE_CODE, null, false, false);
    }

    public String failureCode() {
        return FAILURE_CODE;
    }
}
