package space.br1440.platform.tracing.otel.span.spec;

import java.util.List;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanRelationship;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecBuilder;

import lombok.experimental.UtilityClass;

@UtilityClass
final class OperationSpanSpecs {

    @Nonnull
    static SpanSpec from(@Nonnull String name,
                         @Nonnull SpanCategory category,
                         @Nonnull SpanRelationship relationship,
                         @Nonnull List<RemoteSpanLink> links) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }

        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(links, "links");

        SpanSpecBuilder builder = SpanSpec.builder(name).category(category);
        return SpanSpecBuilderSupport.applyRelationship(builder, relationship, links).build();
    }
}
