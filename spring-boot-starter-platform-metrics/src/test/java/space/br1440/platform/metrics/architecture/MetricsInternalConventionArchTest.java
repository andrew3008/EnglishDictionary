package space.br1440.platform.metrics.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.metrics.PlatformMetrics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Конвенция {@code internal}: инфраструктура построения метрик не протекает в interceptors/http/grpc-адаптеры.
 */
@AnalyzeClasses(packagesOf = PlatformMetrics.class)
class MetricsInternalConventionArchTest {

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule factoryTypesResideInInternalFactory = classes()
            .that().haveSimpleName("HttpMetricFactory")
            .or().haveSimpleName("GrpcMetricFactory")
            .or().haveSimpleName("KafkaPlatformMetricFactory")
            .or().haveSimpleName("MetricFactories")
            .should().resideInAPackage("space.br1440.platform.metrics.internal.factory");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule sloHelperResidesInInternal = classes()
            .that().haveSimpleName("SloHelper")
            .should().resideInAPackage("space.br1440.platform.metrics.internal");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule adaptersAndConfigurationDoNotDependOnInternal = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.metrics.kafka..",
                    "space.br1440.platform.metrics.http..",
                    "space.br1440.platform.metrics.grpc..",
                    "space.br1440.platform.metrics.webflux..",
                    "space.br1440.platform.metrics.configuration..",
                    "space.br1440.platform.metrics.buildinfo.."
            )
            .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.metrics.internal..");
}
