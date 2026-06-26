package space.br1440.platform.errorhandling.core.strategy.cause.core;

public final class PropagationMetadataKeys {

    private PropagationMetadataKeys() {
        throw new AssertionError("Utility class — instantiation is not allowed");
    }

    public static final String PROPAGATED = "propagated";
    public static final String PROPAGATED_ERROR_CODE = "propagatedErrorCode";

    public static final String EXCEPTION_CLASS = "exceptionClass";
    public static final String CAUSE_MESSAGE = "causeMessage";

    public static final String TRUE = "true";

}
