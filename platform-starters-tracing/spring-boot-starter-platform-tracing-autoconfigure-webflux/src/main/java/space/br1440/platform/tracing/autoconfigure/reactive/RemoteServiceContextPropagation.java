package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

@UtilityClass
public final class RemoteServiceContextPropagation {

    private static volatile boolean registered;

    public static void registerIfAbsent() {
        if (registered) {
            return;
        }

        synchronized (RemoteServiceContextPropagation.class) {
            if (registered) {
                return;
            }

            ContextRegistry.getInstance().registerThreadLocalAccessor(new RemoteServiceAccessor());
            registered = true;
        }
    }

    private static final class RemoteServiceAccessor implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return TracingMdcKeys.REMOTE_SERVICE;
        }

        @Override
        public String getValue() {
            return MDC.get(TracingMdcKeys.REMOTE_SERVICE);
        }

        @Override
        public void setValue(String value) {
            if (value == null) {
                MDC.remove(TracingMdcKeys.REMOTE_SERVICE);
            } else {
                MDC.put(TracingMdcKeys.REMOTE_SERVICE, value);
            }
        }

        @Override
        public void reset() {
            MDC.remove(TracingMdcKeys.REMOTE_SERVICE);
        }
    }
}
