package space.br1440.platform.tracing.autoconfigure.support;

import java.util.Collection;
import java.util.Set;

/**
 * Test-only classloader that hides selected package prefixes from {@link #loadClass(String)}.
 * Used in Slice 0B autoconfigure RED tests to reason about classpath-driven conditions.
 */
public final class FilteredClassLoader extends ClassLoader {

    private final Set<String> excludedPackagePrefixes;

    public FilteredClassLoader(ClassLoader parent, Collection<String> excludedPackagePrefixes) {
        super(parent);
        this.excludedPackagePrefixes = Set.copyOf(excludedPackagePrefixes);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String prefix : excludedPackagePrefixes) {
            if (name.startsWith(prefix)) {
                throw new ClassNotFoundException("Filtered: " + name);
            }
        }
        return super.loadClass(name, resolve);
    }
}
