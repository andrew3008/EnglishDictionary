package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistration;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 * Защищает зарегистрированный экземпляр от снятия не-владельцем и хранит
 * идентификаторы ownership вне публичного {@link ObjectName}.
 */
final class OwnedDynamicMBean implements DynamicMBean, MBeanRegistration {

    private final DynamicMBean delegate;
    private final ObjectName expectedName;
    private final UUID batchId;
    private final UUID registrationId;
    private final ThreadLocal<Boolean> ownerDeregistration = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean registered = new AtomicBoolean();

    OwnedDynamicMBean(DynamicMBean delegate, ObjectName expectedName, UUID batchId, UUID registrationId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.expectedName = Objects.requireNonNull(expectedName, "expectedName");
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.registrationId = Objects.requireNonNull(registrationId, "registrationId");
    }

    @SuppressWarnings("unused")
    UUID batchId() {
        return batchId;
    }

    UUID registrationId() {
        return registrationId;
    }

    void unregisterOwned(MBeanServer server) throws Exception {
        Objects.requireNonNull(server, "server");
        if (!registered.get()) {
            return;
        }

        ownerDeregistration.set(true);
        try {
            server.unregisterMBean(expectedName);
        } finally {
            ownerDeregistration.remove();
        }
    }

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName name) {
        if (!expectedName.equals(name)) {
            throw new IllegalArgumentException("Unexpected JMX ObjectName for owned registration");
        }
        return name;
    }

    @Override
    public void postRegister(Boolean registrationDone) {
        registered.set(Boolean.TRUE.equals(registrationDone));
    }

    @Override
    public void preDeregister() throws Exception {
        if (!Boolean.TRUE.equals(ownerDeregistration.get())) {
            throw new MBeanRegistrationException(
                    new IllegalStateException("JMX deregistration rejected: registration ownership mismatch")
            );
        }
    }

    @Override
    public void postDeregister() {
        registered.set(false);
    }

    @Override
    public Object getAttribute(String attribute)
            throws AttributeNotFoundException, MBeanException, ReflectionException {
        return delegate.getAttribute(attribute);
    }

    @Override
    public void setAttribute(Attribute attribute)
            throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException {
        delegate.setAttribute(attribute);
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        return delegate.getAttributes(attributes);
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return delegate.setAttributes(attributes);
    }

    @Override
    public Object invoke(String actionName, Object[] params, String[] signature) throws MBeanException, ReflectionException {
        return delegate.invoke(actionName, params, signature);
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return delegate.getMBeanInfo();
    }
}
