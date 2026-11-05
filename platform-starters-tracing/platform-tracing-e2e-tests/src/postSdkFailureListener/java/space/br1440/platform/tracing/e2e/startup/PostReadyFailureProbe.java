package space.br1440.platform.tracing.e2e.startup;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

public final class PostReadyFailureProbe {

    public static final String RESULT_PREFIX = "POST_READY_FAILURE_PROBE";
    public static final String MBEAN_PREFIX = "POST_READY_FAILURE_PROBE_MBEAN";
    public static final String READINESS_OBJECT_NAME_PROPERTY = "platform.tracing.e2e.readiness-object-name";
    public static final String READINESS_ATTRIBUTE_PROPERTY = "platform.tracing.e2e.readiness-attribute";
    public static final String LIFECYCLE_FIELD_PROPERTY = "platform.tracing.e2e.readiness-lifecycle-field";

    private PostReadyFailureProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("Post-READY failure probe does not accept arguments");
        }

        // Константы свойств инлайнятся компилятором: класс отказа не загружается application classloader.
        Object thrown = System.getProperties().get(PostReadyLinkageFailure.THROWN_PROPERTY);
        Object printed = System.getProperties().get(PostReadyLinkageFailure.PRINTED_PROPERTY);

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName readiness = new ObjectName(requiredProperty(READINESS_OBJECT_NAME_PROPERTY));
        Object snapshot = server.getAttribute(readiness, requiredProperty(READINESS_ATTRIBUTE_PROPERTY));
        if (!(snapshot instanceof CompositeData compositeSnapshot)) {
            throw new IllegalStateException("Readiness snapshot must be CompositeData");
        }

        System.out.printf(
                Locale.ROOT,
                "%s identity=%s lifecycle=%s%n",
                RESULT_PREFIX,
                identity(thrown, printed),
                compositeSnapshot.get(requiredProperty(LIFECYCLE_FIELD_PROPERTY))
        );
        server.queryNames(new ObjectName(readiness.getDomain() + ":*"), null).stream()
                .map(ObjectName::getCanonicalName)
                .sorted()
                .forEach(name -> System.out.println(MBEAN_PREFIX + " " + name));
    }

    private static String identity(Object thrown, Object printed) {
        if (thrown == null || printed == null) {
            return "MISSING";
        }

        return thrown == printed ? "SAME" : "DIFFERENT";
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required probe system property is missing: " + name);
        }

        return value;
    }
}
