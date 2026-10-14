package space.br1440.platform.tracing.autoconfigure.internal;

import java.util.Objects;
import java.util.function.BiConsumer;

import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class WinningPropertySourceTraversal {

    private static final String CONFIGURATION_PROPERTIES_FACADE = "configurationProperties";

    public static PropertySource<?> findWinningLeaf(ConfigurableEnvironment environment, String key) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(key, "key");

        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            PropertySource<?> winner = findWinningLeaf(propertySource, key);
            if (winner != null) {
                return winner;
            }
        }

        return null;
    }

    public static void visitPropertyNames(ConfigurableEnvironment environment,
                                          BiConsumer<PropertySource<?>, String> visitor) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(visitor, "visitor");

        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            visitPropertyNames(propertySource, visitor);
        }
    }

    private static void visitPropertyNames(PropertySource<?> propertySource,
                                           BiConsumer<PropertySource<?>, String> visitor) {
        if (CONFIGURATION_PROPERTIES_FACADE.equals(propertySource.getName())) {
            return;
        }

        if (propertySource instanceof CompositePropertySource composite) {
            for (PropertySource<?> nested : composite.getPropertySources()) {
                visitPropertyNames(nested, visitor);
            }
            return;
        }

        if (propertySource instanceof EnumerablePropertySource<?> enumerable) {
            for (String name : enumerable.getPropertyNames()) {
                visitor.accept(propertySource, name);
            }
        }
    }

    private static PropertySource<?> findWinningLeaf(PropertySource<?> propertySource, String key) {
        if (CONFIGURATION_PROPERTIES_FACADE.equals(propertySource.getName())) {
            return null;
        }

        if (propertySource instanceof CompositePropertySource composite) {
            for (PropertySource<?> nested : composite.getPropertySources()) {
                PropertySource<?> winner = findWinningLeaf(nested, key);
                if (winner != null) {
                    return winner;
                }
            }

            return null;
        }

        return propertySource.containsProperty(key) ? propertySource : null;
    }
}
