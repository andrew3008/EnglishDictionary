package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import java.io.ObjectInputStream;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.InvalidAttributeValueException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.OperationsException;
import javax.management.QueryExp;
import javax.management.ReflectionException;
import javax.management.loading.ClassLoaderRepository;
import javax.management.remote.MBeanServerForwarder;

/**
 * Connector-level forwarder с точным observation allowlist и запретом по умолчанию.
 *
 * <p>Экземпляр устанавливается только на platform-owned remote connector. Локальные обращения к
 * platform MBeanServer идут мимо него; поэтому любой вызов этого класса считается удалённым.</p>
 */
public final class DefaultDenyMBeanServerForwarder implements MBeanServerForwarder {

    private static final int MAX_ATTRIBUTES_PER_REQUEST = 1;

    private final RemoteJmxAccessProfile accessProfile;
    private volatile MBeanServer mBeanServer;

    public DefaultDenyMBeanServerForwarder(RemoteJmxAccessProfile accessProfile) {
        this.accessProfile = Objects.requireNonNull(accessProfile, "Remote JMX access profile must not be null");
    }

    @Override
    public MBeanServer getMBeanServer() {
        return mBeanServer;
    }

    @Override
    public synchronized void setMBeanServer(MBeanServer mBeanServer) {
        if (mBeanServer == null) {
            throw new IllegalArgumentException("MBeanServer must not be null");
        }
        if (this.mBeanServer != null) {
            throw new IllegalArgumentException("MBeanServer has already been assigned");
        }
        this.mBeanServer = mBeanServer;
    }

    @Override
    public ObjectInstance createMBean(String className, ObjectName name)
            throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException,
            MBeanException, NotCompliantMBeanException {
        throw denied("createMBean", name);
    }

    @Override
    public ObjectInstance createMBean(String className, ObjectName name, ObjectName loaderName)
            throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException,
            MBeanException, NotCompliantMBeanException, InstanceNotFoundException {
        throw denied("createMBean", name);
    }

    @Override
    public ObjectInstance createMBean(
            String className,
            ObjectName name,
            Object[] params,
            String[] signature)
            throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException,
            MBeanException, NotCompliantMBeanException {
        throw denied("createMBean", name);
    }

    @Override
    public ObjectInstance createMBean(
            String className,
            ObjectName name,
            ObjectName loaderName,
            Object[] params,
            String[] signature)
            throws ReflectionException, InstanceAlreadyExistsException, MBeanRegistrationException,
            MBeanException, NotCompliantMBeanException, InstanceNotFoundException {
        throw denied("createMBean", name);
    }

    @Override
    public ObjectInstance registerMBean(Object object, ObjectName name)
            throws InstanceAlreadyExistsException, MBeanRegistrationException, NotCompliantMBeanException {
        throw denied("registerMBean", name);
    }

    @Override
    public void unregisterMBean(ObjectName name)
            throws InstanceNotFoundException, MBeanRegistrationException {
        throw denied("unregisterMBean", name);
    }

    @Override
    public ObjectInstance getObjectInstance(ObjectName name) throws InstanceNotFoundException {
        requireObjectAllowed("getObjectInstance", name);
        return server().getObjectInstance(name);
    }

    @Override
    public Set<ObjectInstance> queryMBeans(ObjectName name, QueryExp query) {
        requireSafeQuery(query);
        Set<ObjectInstance> result = new LinkedHashSet<>();
        for (ObjectName allowed : matchingRegisteredNames(name)) {
            try {
                result.add(server().getObjectInstance(allowed));
            } catch (InstanceNotFoundException ignored) {
                // MBean мог быть снят между isRegistered и чтением; в результат он не попадает.
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public Set<ObjectName> queryNames(ObjectName name, QueryExp query) {
        requireSafeQuery(query);
        return Set.copyOf(matchingRegisteredNames(name));
    }

    @Override
    public boolean isRegistered(ObjectName name) {
        requireObjectAllowed("isRegistered", name);
        return server().isRegistered(name);
    }

    @Override
    public Integer getMBeanCount() {
        return matchingRegisteredNames(null).size();
    }

    @Override
    public Object getAttribute(ObjectName name, String attribute)
            throws MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException {
        requireAttributeAllowed(name, attribute);
        return readValidatedAttribute("getAttribute", name, attribute);
    }

    @Override
    public AttributeList getAttributes(ObjectName name, String[] attributes)
            throws InstanceNotFoundException, ReflectionException {
        if (attributes == null) {
            throw denied("getAttributes:missing-attributes", name);
        }
        requireObjectAllowed("getAttributes", name);
        if (attributes.length > MAX_ATTRIBUTES_PER_REQUEST) {
            String violation = Objects.equals(attributes[0], attributes[1])
                    ? "getAttributes:duplicate-attribute"
                    : "getAttributes:attribute-count";
            throw denied(violation, name);
        }

        String[] requestedAttributes = attributes.clone();
        for (String attribute : requestedAttributes) {
            requireAttributeAllowed(name, attribute);
        }
        if (requestedAttributes.length == 0) {
            requireValidMetadata("getAttributes", name);
            return new AttributeList();
        }

        Object value = readValidatedAttribute(
                "getAttributes",
                name,
                requestedAttributes[0]);
        AttributeList result = new AttributeList(1);
        result.add(new Attribute(requestedAttributes[0], value));
        return result;
    }

    @Override
    public void setAttribute(ObjectName name, Attribute attribute)
            throws InstanceNotFoundException, AttributeNotFoundException, InvalidAttributeValueException,
            MBeanException, ReflectionException {
        throw denied("setAttribute", name);
    }

    @Override
    public AttributeList setAttributes(ObjectName name, AttributeList attributes)
            throws InstanceNotFoundException, ReflectionException {
        throw denied("setAttributes", name);
    }

    @Override
    public Object invoke(ObjectName name, String operationName, Object[] params, String[] signature)
            throws InstanceNotFoundException, MBeanException, ReflectionException {
        throw denied("invoke:" + safeToken(operationName), name);
    }

    @Override
    public String getDefaultDomain() {
        return server().getDefaultDomain();
    }

    @Override
    public String[] getDomains() {
        return matchingRegisteredNames(null).stream()
                .map(ObjectName::getDomain)
                .distinct()
                .sorted()
                .toArray(String[]::new);
    }

    @Override
    public void addNotificationListener(
            ObjectName name,
            NotificationListener listener,
            NotificationFilter filter,
            Object handback) throws InstanceNotFoundException {
        throw denied("addNotificationListener", name);
    }

    @Override
    public void addNotificationListener(
            ObjectName name,
            ObjectName listener,
            NotificationFilter filter,
            Object handback) throws InstanceNotFoundException {
        throw denied("addNotificationListener", name);
    }

    @Override
    public void removeNotificationListener(ObjectName name, ObjectName listener)
            throws InstanceNotFoundException, ListenerNotFoundException {
        throw denied("removeNotificationListener", name);
    }

    @Override
    public void removeNotificationListener(
            ObjectName name,
            ObjectName listener,
            NotificationFilter filter,
            Object handback) throws InstanceNotFoundException, ListenerNotFoundException {
        throw denied("removeNotificationListener", name);
    }

    @Override
    public void removeNotificationListener(ObjectName name, NotificationListener listener)
            throws InstanceNotFoundException, ListenerNotFoundException {
        throw denied("removeNotificationListener", name);
    }

    @Override
    public void removeNotificationListener(
            ObjectName name,
            NotificationListener listener,
            NotificationFilter filter,
            Object handback) throws InstanceNotFoundException, ListenerNotFoundException {
        throw denied("removeNotificationListener", name);
    }

    @Override
    public MBeanInfo getMBeanInfo(ObjectName name)
            throws InstanceNotFoundException, IntrospectionException, ReflectionException {
        requireObjectAllowed("getMBeanInfo", name);
        return requireValidMetadata("getMBeanInfo", name).canonicalInfo();
    }

    @Override
    public boolean isInstanceOf(ObjectName name, String className) throws InstanceNotFoundException {
        throw denied("isInstanceOf", name);
    }

    @Override
    public Object instantiate(String className) throws ReflectionException, MBeanException {
        throw denied("instantiate", null);
    }

    @Override
    public Object instantiate(String className, ObjectName loaderName)
            throws ReflectionException, MBeanException, InstanceNotFoundException {
        throw denied("instantiate", loaderName);
    }

    @Override
    public Object instantiate(String className, Object[] params, String[] signature)
            throws ReflectionException, MBeanException {
        throw denied("instantiate", null);
    }

    @Override
    public Object instantiate(
            String className,
            ObjectName loaderName,
            Object[] params,
            String[] signature) throws ReflectionException, MBeanException, InstanceNotFoundException {
        throw denied("instantiate", loaderName);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ObjectInputStream deserialize(ObjectName name, byte[] data)
            throws InstanceNotFoundException, OperationsException {
        throw denied("deserialize", name);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ObjectInputStream deserialize(String className, byte[] data)
            throws OperationsException, ReflectionException {
        throw denied("deserialize", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ObjectInputStream deserialize(String className, ObjectName loaderName, byte[] data)
            throws InstanceNotFoundException, OperationsException, ReflectionException {
        throw denied("deserialize", loaderName);
    }

    @Override
    public ClassLoader getClassLoaderFor(ObjectName mbeanName) throws InstanceNotFoundException {
        if (!RemoteJmxAccessProfile.isGeneratedContractEndpoint(mbeanName)) {
            throw denied("getClassLoaderFor", mbeanName);
        }
        return server().getClassLoaderFor(mbeanName);
    }

    @Override
    public ClassLoader getClassLoader(ObjectName loaderName) throws InstanceNotFoundException {
        throw denied("getClassLoader", loaderName);
    }

    @Override
    public ClassLoaderRepository getClassLoaderRepository() {
        return server().getClassLoaderRepository();
    }

    private MBeanServer server() {
        MBeanServer assigned = mBeanServer;
        if (assigned == null) {
            throw new IllegalStateException("MBeanServer has not been assigned");
        }
        return assigned;
    }

    private Set<ObjectName> matchingRegisteredNames(ObjectName requestedName) {
        Set<ObjectName> names = new LinkedHashSet<>();
        for (ObjectName allowed : accessProfile.objectNames()) {
            if (RemoteJmxAccessProfile.isGeneratedRemoteReadEndpoint(allowed)
                    && (requestedName == null || requestedName.apply(allowed))
                    && server().isRegistered(allowed)) {
                names.add(allowed);
            }
        }
        return names;
    }

    private void requireObjectAllowed(String operation, ObjectName name) {
        requireGeneratedRemoteReadEndpoint(operation, name);
        if (!accessProfile.allowsObjectName(name)) {
            throw denied(operation, name);
        }
    }

    private void requireAttributeAllowed(ObjectName name, String attribute) {
        requireGeneratedRemoteReadEndpoint("getAttribute:" + safeToken(attribute), name);
        if (!RemoteJmxAccessProfile.isGeneratedRemoteReadAttribute(name, attribute)
                || !accessProfile.allowsAttribute(name, attribute)) {
            throw denied("getAttribute:" + safeToken(attribute), name);
        }
    }

    private RemoteJmxReadGuard.Endpoint requireValidMetadata(String operation, ObjectName name) {
        RemoteJmxReadGuard.Endpoint endpoint = RemoteJmxReadGuard.endpoint(name);
        if (endpoint == null) {
            throw denied(operation + ":metadata-violation", name);
        }

        MBeanServer assigned = server();
        try {
            endpoint.validateMetadata(assigned.getMBeanInfo(name));
            return endpoint;
        } catch (InstanceNotFoundException | IntrospectionException | ReflectionException | RuntimeException failure) {
            throw denied(operation + ":metadata-violation", name);
        }
    }

    private Object readValidatedAttribute(String operation, ObjectName name, String attribute) {
        RemoteJmxReadGuard.Endpoint endpoint = requireValidMetadata(operation, name);
        MBeanServer assigned = server();
        Object value;
        try {
            value = assigned.getAttribute(name, attribute);
        } catch (MBeanException | AttributeNotFoundException | InstanceNotFoundException
                 | ReflectionException | RuntimeException failure) {
            throw denied(operation + ":read-failed", name);
        }

        try {
            endpoint.validatePayload(value);
            return value;
        } catch (RuntimeException contractViolation) {
            throw denied(operation + ":payload-violation", name);
        }
    }

    private static void requireSafeQuery(QueryExp query) {
        if (query != null) {
            throw denied("query-with-expression", null);
        }
    }

    private static String safeToken(String value) {
        return value == null || value.isBlank() ? "<missing>" : "<provided>";
    }

    private static void requireGeneratedRemoteReadEndpoint(String operation, ObjectName name) {
        if (!RemoteJmxAccessProfile.isGeneratedRemoteReadEndpoint(name)) {
            throw denied(operation, name);
        }
    }

    private static SecurityException denied(String operation, ObjectName name) {
        String target;
        if (name == null) {
            target = "MBeanServer";
        } else if (RemoteJmxAccessProfile.isGeneratedRemoteReadEndpoint(name)) {
            target = name.getCanonicalName();
        } else {
            target = "<unapproved-object-name>";
        }
        return new SecurityException("Remote JMX access denied: operation=" + operation + ", target=" + target);
    }
}
