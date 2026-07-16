package space.br1440.platform.tracing.core.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * PR-3 ArchUnit guardrails для {@code core.mdc.remote}.
 * <p>
 * Проверяет три инварианта пакета:
 * <ol>
 *   <li>{@code RemoteServiceTraceMirror} — package-private (не public, не protected).</li>
 *   <li>{@code RemoteServiceMdc} и {@code RemoteServiceNameResolver} живут только в {@code core.mdc.remote}.</li>
 *   <li>Удалённый {@code RemoteServiceContextReaders} (anti-pattern PR-2) нигде не используется.</li>
 * </ol>
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CoreMdcRemoteArchTest {

    /**
     * {@code RemoteServiceTraceMirror} — package-private trace-scoped map;
     * пишет только {@code RemoteServiceMdc}, читает только {@code RemoteServiceNameResolver}.
     * Оба класса — в одном пакете, поэтому public-видимость не нужна.
     */
    @ArchTest
    static final ArchRule traceMirrorIsPackagePrivate =
            ModuleTaxonomyArchRules.TRACE_MIRROR_PACKAGE_PRIVATE;

    /**
     * Implementation-типы MDC живут только в {@code core.mdc.remote}.
     * Предотвращает случайный возврат implementation в api-пакет.
     */
    @ArchTest
    static final ArchRule remoteServiceMdcImplOnlyInCore =
            ModuleTaxonomyArchRules.REMOTE_SERVICE_MDC_IMPL_ONLY_IN_CORE;

    /**
     * Удалённый {@code RemoteServiceContextReaders} (mutable global static registry) нигде
     * не должен использоваться. Правило ловит откат при cherry-pick из старой ветки.
     */
    @ArchTest
    static final ArchRule noRemoteServiceContextReaders =
            ModuleTaxonomyArchRules.NO_REMOTE_SERVICE_CONTEXT_READERS;
}
