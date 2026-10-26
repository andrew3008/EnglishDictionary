package space.br1440.platform.tracing.gradle.javaagent;

import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.model.ObjectFactory;

public final class PackagedExtensionAttributes {

    private static final String PACKAGED_LIBRARY_ELEMENTS = "javaagent-extension-jar";

    private PackagedExtensionAttributes() {
    }

    public static void configure(ObjectFactory objects, AttributeContainer attributes) {
        attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage.class, Usage.JAVA_RUNTIME)
        );

        attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                objects.named(Category.class, Category.LIBRARY)
        );

        attributes.attribute(
                Bundling.BUNDLING_ATTRIBUTE,
                objects.named(Bundling.class, Bundling.SHADOWED)
        );

        attributes.attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements.class, PACKAGED_LIBRARY_ELEMENTS)
        );
    }
}
