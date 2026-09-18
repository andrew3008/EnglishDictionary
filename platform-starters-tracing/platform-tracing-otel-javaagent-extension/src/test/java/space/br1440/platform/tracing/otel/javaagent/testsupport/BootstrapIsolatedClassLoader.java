package space.br1440.platform.tracing.otel.javaagent.testsupport;

import java.io.IOException;
import java.io.InputStream;

public final class BootstrapIsolatedClassLoader extends ClassLoader {

    private static final String BOOTSTRAP_PACKAGE_PREFIX = "space.br1440.platform.tracing.otel.javaagent.bootstrap.";

    private BootstrapIsolatedClassLoader(ClassLoader parent) {
        super(parent);
    }

    public static ClassLoader freshBootstrapClassLoader() {
        ClassLoader classLoader = BootstrapIsolatedClassLoader.class.getClassLoader();
        return freshBootstrapClassLoader(classLoader);
    }

    public static ClassLoader freshBootstrapClassLoader(ClassLoader parent) {
        return new BootstrapIsolatedClassLoader(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = name.startsWith(BOOTSTRAP_PACKAGE_PREFIX) ? defineOwnClass(name) : getParent().loadClass(name);
            }

            if (resolve) {
                resolveClass(loaded);
            }

            return loaded;
        }
    }

    private Class<?> defineOwnClass(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = getParent().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }

            byte[] bytes = in.readAllBytes();
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
