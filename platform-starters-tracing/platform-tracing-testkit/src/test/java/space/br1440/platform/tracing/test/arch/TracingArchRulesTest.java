package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.micrometer.observation.annotation.Observed;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.annotation.Traced;
import space.br1440.platform.tracing.api.annotation.TracedAttribute;
import space.br1440.platform.tracing.api.span.SpanFactory;
import space.br1440.platform.tracing.api.span.enrich.GenericSpanEnrichment;
import space.br1440.platform.tracing.api.span.enrich.SpanEnricher;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Self-test для {@link TracingArchRules}: правила сначала валятся на синтетических нарушителях
 * (включая meta-annotation case W7 и class-level effective {@code @Traced}), затем не срабатывают
 * на корректных классах.
 * <p>
 * Покрываемые правила:
 * <ul>
 *   <li>{@link TracingArchRules#NO_TRACED_AND_OBSERVED_ON_SAME_METHOD}</li>
 *   <li>{@link TracingArchRules#NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD}</li>
 *   <li>{@link TracingArchRules#TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST}</li>
 * </ul>
 */
class TracingArchRulesTest {

    // -------------------------------------------------------------------------
    // NO_TRACED_AND_OBSERVED_ON_SAME_METHOD
    // -------------------------------------------------------------------------

    @Test
    void allowsTracedOnly() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(GoodTracedOnly.class);

        // when
        TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes);

        // then
        assertThat(classes).isNotEmpty();
    }

    @Test
    void allowsObservedOnly() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(GoodObservedOnly.class);

        // when
        TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes);

        // then
        assertThat(classes).isNotEmpty();
    }

    @Test
    void allowsClassLevelTracedWithoutObserved() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(GoodClassLevelTracedOnly.class);

        // when
        TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes);

        // then
        assertThat(classes).isNotEmpty();
    }

    @Test
    void detectsBothAnnotationsOnSameMethod() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadBothAnnotations.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("@Observed");
    }

    /**
     * W7: правило учитывает meta-аннотации. Кастомная {@code @CustomObserved}, помеченная
     * {@code @Observed}, считается эквивалентной для целей правила.
     */
    @Test
    void detectsTracedPlusMetaAnnotatedObserved() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadTracedPlusMetaObserved.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes))
                .hasMessageContaining("@Traced");
    }

    @Test
    void detectsClassLevelTracedPlusMethodObserved() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadClassTracedMethodObserved.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("@Observed");
    }

    @Test
    void detectsClassLevelTracedPlusMetaAnnotatedObserved() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadClassTracedMethodMetaObserved.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_AND_OBSERVED_ON_SAME_METHOD.check(classes))
                .hasMessageContaining("@Traced");
    }

    // -------------------------------------------------------------------------
    // NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD
    // -------------------------------------------------------------------------

    @Test
    void allowsMethodTracedOnSynchronousReturn() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(GoodMethodTracedSync.class);

        // when
        TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes);

        // then
        assertThat(classes).isNotEmpty();
    }

    @Test
    void allowsClassTracedOnSynchronousReturn() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(GoodClassTracedSync.class);

        // when
        TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes);

        // then
        assertThat(classes).isNotEmpty();
    }

    @Test
    void allowsAsyncAndReactiveWithoutTraced() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(
                GoodAsyncWithoutTraced.class,
                GoodReactiveWithoutTraced.class,
                GoodFutureWithoutTraced.class);

        // when
        TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes);

        // then
        assertThat(classes).isNotEmpty();
    }

    @Test
    void detectsMethodTracedOnCompletableFuture() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadMethodTracedCompletableFuture.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("CompletableFuture");
    }

    @Test
    void detectsMethodTracedOnCompletionStage() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadMethodTracedCompletionStage.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("CompletionStage");
    }

    @Test
    void detectsMethodTracedOnMono() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadMethodTracedMono.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("Mono");
    }

    @Test
    void detectsMethodTracedOnFlux() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadMethodTracedFlux.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("Flux");
    }

    @Test
    void detectsMethodTracedPlusAsync() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadMethodTracedAsync.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("@Async");
    }

    @Test
    void detectsClassTracedOnCompletableFuture() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadClassTracedCompletableFuture.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("CompletableFuture");
    }

    @Test
    void detectsClassTracedOnCompletionStage() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadClassTracedCompletionStage.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("CompletionStage");
    }

    @Test
    void detectsClassTracedOnMono() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadClassTracedMono.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("Mono");
    }

    @Test
    void detectsClassTracedOnFlux() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadClassTracedFlux.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("Flux");
    }

    @Test
    void detectsClassTracedPlusMethodAsync() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadClassTracedMethodAsync.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD.check(classes))
                .hasMessageContaining("@Traced")
                .hasMessageContaining("@Async");
    }

    // -------------------------------------------------------------------------
    // TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST
    // -------------------------------------------------------------------------

    @Test
    void allowsTracedAttributeOnAllowlistedParameterTypes() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(GoodTracedAttributeAllowlistedTypes.class, SampleAttributeEnum.class);

        // when
        TracingArchRules.TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST.check(classes);

        // then
        assertThat(classes).isNotEmpty();
    }

    @Test
    void detectsTracedAttributeOnNumber() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadTracedAttributeNumber.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST.check(classes))
                .hasMessageContaining("@TracedAttribute")
                .hasMessageContaining("Number");
    }

    @Test
    void detectsTracedAttributeOnDomainObject() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadTracedAttributeDomainObject.class, SampleDomainObject.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST.check(classes))
                .hasMessageContaining("@TracedAttribute")
                .hasMessageContaining("SampleDomainObject");
    }

    @Test
    void detectsTracedAttributeOnList() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadTracedAttributeList.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST.check(classes))
                .hasMessageContaining("@TracedAttribute")
                .hasMessageContaining("List");
    }

    @Test
    void detectsTracedAttributeOnArray() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(BadTracedAttributeArray.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST.check(classes))
                .hasMessageContaining("@TracedAttribute");
    }

    @Test
    void detectsTracedAttributeOnOptional() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importClasses(BadTracedAttributeOptional.class);

        // when
        // then
        assertThatThrownBy(() -> TracingArchRules.TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST.check(classes))
                .hasMessageContaining("@TracedAttribute")
                .hasMessageContaining("Optional");
    }

    // -------------------------------------------------------------------------
    // Прочие API-shape guards (существующие)
    // -------------------------------------------------------------------------

    @Test
    void traceOperationsAndSpanFactoryKeepApprovedApiShape() throws NoSuchMethodException {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importPackages("space.br1440.platform.tracing");

        // when
        // then
        noClasses()
                .that().resideInAPackage("space.br1440.platform.tracing..")
                .should().haveSimpleName("Manual" + "Tracing")
                .check(classes);

        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.span.builder." + "Manual" + "Tracing")).isFalse();

        assertThat(TraceOperations.class.getMethod("spans").getReturnType()).isEqualTo(SpanFactory.class);
        assertThat(hasNoArgMethod(TraceOperations.class, "manual")).isFalse();

        assertThat(SpanFactory.class.getMethod("operation", String.class).getReturnType().getSimpleName())
                .isEqualTo("OperationSpanBuilder");
        assertThat(hasMethod(SpanFactory.class, "operation" + "Span", String.class)).isFalse();
        assertThat(SpanFactory.class.getMethod("fromSpec", SpanSpec.class).getReturnType().getSimpleName())
                .isEqualTo("SpanExecution");
        assertThat(hasMethod(SpanFactory.class, "span" + "FromSpec", SpanSpec.class)).isFalse();
    }

    @Test
    void traceparentReaderKeepsApprovedApiShapeAndAccess() {
        // given
        JavaClasses classes = new ClassFileImporter()
                .importPackages(
                        "space.br1440.platform.tracing.api",
                        "space.br1440.platform.tracing.otel");

        // when
        // then
        ModuleTaxonomyArchRules.API_PROPAGATION_HAS_NO_PUBLIC_PARSERS.check(classes);
        ModuleTaxonomyArchRules.OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED.check(classes);
        ModuleTaxonomyArchRules.API_HAS_NO_REQUEST_ID_SUPPORT_SPI.check(classes);
        ModuleTaxonomyArchRules.API_NO_SERVICE_LOADER.check(classes);
        ModuleTaxonomyArchRules.REQUEST_ID_SUPPORT_CORE_UTILITY_IS_DEPENDENCY_LIGHT.check(classes);
        ModuleTaxonomyArchRules.CONTROL_IMPLS_ONLY_IN_CORE.check(classes);
        ModuleTaxonomyArchRules.CONTROL_IMPL_ACCESS_RESTRICTED.check(classes);
        ModuleTaxonomyArchRules.API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL.check(classes);

        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.propagation." + "Traceparent" + "Parser"))
                .isFalse();
    }

    @Test
    void spanEnrichmentKeepsApprovedApiShape() {
        // given
        // when
        // then
        assertThat(SpanEnricher.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactly("enrichCurrentSpan");
        assertThat(GenericSpanEnrichment.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("requestId", "userHash", "result");

        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.span.enrich.SpanEnrichment"))
                .isFalse();
        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.semconv.SemconvKeys"))
                .isFalse();
        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.span.sanitize.UrlSanitizer"))
                .isFalse();
        assertThat(classCanBeLoaded("space.br1440.platform.tracing.api.span.sanitize.SqlSanitizer"))
                .isFalse();
    }

    private static boolean classCanBeLoaded(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean hasNoArgMethod(Class<?> type, String name) {
        return hasMethod(type, name);
    }

    private static boolean hasMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures: NO_TRACED_AND_OBSERVED_ON_SAME_METHOD
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    static class GoodTracedOnly {
        @Traced("good.traced")
        void run() {
        }
    }

    @SuppressWarnings("unused")
    static class GoodObservedOnly {
        @Observed(name = "good.observed")
        void run() {
        }
    }

    @Traced("good.class.traced")
    @SuppressWarnings("unused")
    static class GoodClassLevelTracedOnly {
        void run() {
        }
    }

    @SuppressWarnings("unused")
    static class BadBothAnnotations {
        @Traced("bad.both")
        @Observed(name = "bad.both")
        void run() {
        }
    }

    /** Кастомная meta-аннотация: {@code @CustomObserved} → {@code @Observed}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Observed(name = "custom-observed-meta")
    @interface CustomObserved {
    }

    @SuppressWarnings("unused")
    static class BadTracedPlusMetaObserved {
        @Traced("bad.meta")
        @CustomObserved
        void run() {
        }
    }

    @Traced("bad.class.traced")
    @SuppressWarnings("unused")
    static class BadClassTracedMethodObserved {
        @Observed(name = "bad.method.observed")
        void run() {
        }
    }

    @Traced("bad.class.meta")
    @SuppressWarnings("unused")
    static class BadClassTracedMethodMetaObserved {
        @CustomObserved
        void run() {
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures: NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD (positive)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    static class GoodMethodTracedSync {
        @Traced("good.sync")
        String run() {
            return "ok";
        }
    }

    @Traced("good.class.sync")
    @SuppressWarnings("unused")
    static class GoodClassTracedSync {
        String run() {
            return "ok";
        }
    }

    @SuppressWarnings("unused")
    static class GoodAsyncWithoutTraced {
        @Async
        void run() {
        }
    }

    @SuppressWarnings("unused")
    static class GoodReactiveWithoutTraced {
        Mono<String> mono() {
            return Mono.empty();
        }

        Flux<String> flux() {
            return Flux.empty();
        }
    }

    @SuppressWarnings("unused")
    static class GoodFutureWithoutTraced {
        CompletableFuture<String> completableFuture() {
            return CompletableFuture.completedFuture("ok");
        }

        CompletionStage<String> completionStage() {
            return CompletableFuture.completedFuture("ok");
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures: NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD (method-level @Traced)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    static class BadMethodTracedCompletableFuture {
        @Traced("bad.method.cf")
        CompletableFuture<String> run() {
            return CompletableFuture.completedFuture("x");
        }
    }

    @SuppressWarnings("unused")
    static class BadMethodTracedCompletionStage {
        @Traced("bad.method.cs")
        CompletionStage<String> run() {
            return CompletableFuture.completedFuture("x");
        }
    }

    @SuppressWarnings("unused")
    static class BadMethodTracedMono {
        @Traced("bad.method.mono")
        Mono<String> run() {
            return Mono.empty();
        }
    }

    @SuppressWarnings("unused")
    static class BadMethodTracedFlux {
        @Traced("bad.method.flux")
        Flux<String> run() {
            return Flux.empty();
        }
    }

    @SuppressWarnings("unused")
    static class BadMethodTracedAsync {
        @Traced("bad.method.async")
        @Async
        void run() {
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures: NO_TRACED_ON_ASYNC_OR_REACTIVE_METHOD (class-level @Traced)
    // -------------------------------------------------------------------------

    @Traced("bad.class.cf")
    @SuppressWarnings("unused")
    static class BadClassTracedCompletableFuture {
        CompletableFuture<String> run() {
            return CompletableFuture.completedFuture("x");
        }
    }

    @Traced("bad.class.cs")
    @SuppressWarnings("unused")
    static class BadClassTracedCompletionStage {
        CompletionStage<String> run() {
            return CompletableFuture.completedFuture("x");
        }
    }

    @Traced("bad.class.mono")
    @SuppressWarnings("unused")
    static class BadClassTracedMono {
        Mono<String> run() {
            return Mono.empty();
        }
    }

    @Traced("bad.class.flux")
    @SuppressWarnings("unused")
    static class BadClassTracedFlux {
        Flux<String> run() {
            return Flux.empty();
        }
    }

    @Traced("bad.class.async")
    @SuppressWarnings("unused")
    static class BadClassTracedMethodAsync {
        @Async
        void run() {
        }
    }

    // -------------------------------------------------------------------------
    // Fixtures: TRACED_ATTRIBUTE_PARAMETER_TYPE_ALLOWLIST
    // -------------------------------------------------------------------------

    enum SampleAttributeEnum {
        ALPHA,
        BETA
    }

    static final class SampleDomainObject {
        private final String id;

        SampleDomainObject(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    @SuppressWarnings("unused")
    static class GoodTracedAttributeAllowlistedTypes {
        @Traced("good.attr")
        void run(
                @TracedAttribute("pBoolean") boolean pBoolean,
                @TracedAttribute("pByte") byte pByte,
                @TracedAttribute("pShort") short pShort,
                @TracedAttribute("pInt") int pInt,
                @TracedAttribute("pLong") long pLong,
                @TracedAttribute("pFloat") float pFloat,
                @TracedAttribute("pDouble") double pDouble,
                @TracedAttribute("pChar") char pChar,
                @TracedAttribute("bBoolean") Boolean bBoolean,
                @TracedAttribute("bByte") Byte bByte,
                @TracedAttribute("bShort") Short bShort,
                @TracedAttribute("bInteger") Integer bInteger,
                @TracedAttribute("bLong") Long bLong,
                @TracedAttribute("bFloat") Float bFloat,
                @TracedAttribute("bDouble") Double bDouble,
                @TracedAttribute("bCharacter") Character bCharacter,
                @TracedAttribute("string") String string,
                @TracedAttribute("enumValue") SampleAttributeEnum enumValue) {
        }
    }

    @SuppressWarnings("unused")
    static class BadTracedAttributeNumber {
        @Traced("bad.attr.number")
        void run(@TracedAttribute("number") Number number) {
        }
    }

    @SuppressWarnings("unused")
    static class BadTracedAttributeDomainObject {
        @Traced("bad.attr.domain")
        void run(@TracedAttribute("domain") SampleDomainObject domain) {
        }
    }

    @SuppressWarnings("unused")
    static class BadTracedAttributeList {
        @Traced("bad.attr.list")
        void run(@TracedAttribute("list") List<String> list) {
        }
    }

    @SuppressWarnings("unused")
    static class BadTracedAttributeArray {
        @Traced("bad.attr.array")
        void run(@TracedAttribute("array") String[] array) {
        }
    }

    @SuppressWarnings("unused")
    static class BadTracedAttributeOptional {
        @Traced("bad.attr.optional")
        void run(@TracedAttribute("optional") Optional<String> optional) {
        }
    }
}
