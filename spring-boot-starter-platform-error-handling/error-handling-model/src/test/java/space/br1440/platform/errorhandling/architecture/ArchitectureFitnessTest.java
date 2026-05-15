package space.br1440.platform.errorhandling.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.tngtech.archunit.base.DescribedPredicate;
import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.exception.business.BusinessException;
import space.br1440.platform.errorhandling.exception.datasource.DataSourceException;
import space.br1440.platform.errorhandling.exception.httpstatus.BadGatewayException;
import space.br1440.platform.errorhandling.exception.httpstatus.BadRequestException;
import space.br1440.platform.errorhandling.exception.httpstatus.ConflictException;
import space.br1440.platform.errorhandling.exception.httpstatus.ForbiddenException;
import space.br1440.platform.errorhandling.exception.httpstatus.GatewayTimeoutException;
import space.br1440.platform.errorhandling.exception.httpstatus.NotFoundException;
import space.br1440.platform.errorhandling.exception.httpstatus.NotImplementedException;
import space.br1440.platform.errorhandling.exception.httpstatus.ServiceUnavailableException;
import space.br1440.platform.errorhandling.exception.httpstatus.TooManyRequestsException;
import space.br1440.platform.errorhandling.exception.httpstatus.UnauthorizedException;
import space.br1440.platform.errorhandling.exception.httpstatus.UnprocessableException;
import space.br1440.platform.errorhandling.exception.inconsistency.InconsistencyException;
import space.br1440.platform.errorhandling.exception.integration.IntegrationException;
import space.br1440.platform.errorhandling.exception.validation.ValidationException;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ArchUnit-тесты для трёхосной модели исключений (NetCracker-стиль).
 *
 * <p>Инварианты: не-sealed корень; HTTP-маркеры только в {@code exception.httpstatus};
 * семантические маркеры — в своих bucket-пакетах + {@code business.BusinessException};
 * concrete-листья в {@code validation/}, {@code inconsistency/}, {@code integration/},
 * {@code datasource/}, {@code internal/}; запрет legacy {@code Http*Exception} и старых
 * подпакетов {@code business/*}/{@code technical/*}; пакет {@code exception.markers} удалён.
 *
 * <p>{@link space.br1440.platform.errorhandling.startup.PlatformConfigurationException} —
 * вне дерева {@code exception/}; правила с шаблоном {@code ..exception.(*)..} на неё не распространяются.
 */
@DisplayName("Architecture fitness tests")
class ArchitectureFitnessTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.errorhandling");
    }

    @Nested
    @DisplayName("Трёхосная модель (корень + пакеты + маркеры)")
    class ThreeAxisExceptionRules {

        private static final String EXCEPTION_ROOT = "space.br1440.platform.errorhandling.exception";

        private static final List<Class<?>> SEMANTIC_MARKERS = List.of(
                BusinessException.class,
                ValidationException.class,
                InconsistencyException.class,
                DataSourceException.class,
                IntegrationException.class
        );

        private static final List<Class<?>> HTTP_MARKERS = List.of(
                BadRequestException.class,
                UnauthorizedException.class,
                ForbiddenException.class,
                NotFoundException.class,
                ConflictException.class,
                UnprocessableException.class,
                TooManyRequestsException.class,
                NotImplementedException.class,
                BadGatewayException.class,
                ServiceUnavailableException.class,
                GatewayTimeoutException.class
        );

        @Test
        void abstractRoot_isNotSealed() {
            ArchCondition<JavaClass> notSealed = new ArchCondition<>("not be sealed") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    try {
                        Class<?> c = Class.forName(item.getFullName());
                        if (c.isSealed()) {
                            events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " must not be sealed (three-axis model)."));
                        }
                    } catch (ClassNotFoundException e) {
                        events.add(SimpleConditionEvent.violated(item,
                                "Could not load " + item.getFullName() + " for sealed check: " + e));
                    }
                }
            };
            classes()
                    .that().haveSimpleName("AbstractErrorResponseException")
                    .and().resideInAPackage("..exception")
                    .should(notSealed)
                    .because("Корневой AbstractErrorResponseException — обычный abstract (без sealed/permits).")
                    .check(classes);
        }

        @Test
        void concreteInSemanticBuckets_extendAbstractErrorResponseException() {
            classes()
                    .that().resideInAnyPackage(
                            "..exception.validation..",
                            "..exception.inconsistency..",
                            "..exception.integration..",
                            "..exception.datasource..",
                            "..exception.internal..")
                    .and().areNotInterfaces()
                    .should().beAssignableTo(AbstractErrorResponseException.class)
                    .because("Все concrete-листья платформы лежат в пяти семантических подпакетах.")
                    .check(classes);
        }

        @Test
        void noHttpLegacyClassesInExceptionRootPackage() {
            ArchCondition<JavaClass> mustNotAppear = new ArchCondition<>("not exist in exception root") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " — Http* в корне exception/ запрещены (используйте маркеры)."));
                }
            };
            classes()
                    .that(DescribedPredicate.describe("reside in exception root package only",
                            jc -> EXCEPTION_ROOT.equals(jc.getPackageName())))
                    .and().haveSimpleNameStartingWith("Http")
                    .should(mustNotAppear)
                    .allowEmptyShould(true)
                    .because("Промежуточные Http*Exception удалены; HTTP — только маркеры в exception.httpstatus.")
                    .check(classes);
        }

        @Test
        void noLegacyBusinessOrTechnicalSubpackages() {
            ArchCondition<JavaClass> forbidden = new ArchCondition<>("not exist in legacy packages") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " — класс в удалённом legacy-пакете business/technical."));
                }
            };
            classes()
                    .that().resideInAnyPackage(
                            "..exception.business.badrequest..",
                            "..exception.business.conflict..",
                            "..exception.business.notfound..",
                            "..exception.business.unprocessable..",
                            "..exception.business.rule..",
                            "..exception.technical..")
                    .should(forbidden)
                    .allowEmptyShould(true)
                    .because("Старые подпакеты business/* и technical/* удалены (greenfield v2).")
                    .check(classes);
        }

        @Test
        void businessPackage_containsOnlyExtensionPointSurface() {
            ArchCondition<JavaClass> forbidden = new ArchCondition<>(
                    "be only BusinessException or package-info in exception.business") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean ok = "package-info".equals(item.getSimpleName())
                            || BusinessException.class.getName().equals(item.getFullName());
                    if (!ok) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " — в exception.business/ допустимы только "
                                        + "BusinessException и package-info."));
                    }
                }
            };
            classes()
                    .that().resideInAPackage("..exception.business..")
                    .should(forbidden)
                    .allowEmptyShould(true)
                    .because("exception.business/ — точка расширения: маркер BusinessException + package-info.")
                    .check(classes);
        }

        @Test
        void semanticMarkerInterfaces_resideInOwnBucketPackages() {
            classes()
                    .that().haveFullyQualifiedName(ValidationException.class.getName())
                    .should().resideInAPackage("..exception.validation..")
                    .because("ValidationException объявляется в exception.validation (package-by-feature).")
                    .check(classes);
            classes()
                    .that().haveFullyQualifiedName(InconsistencyException.class.getName())
                    .should().resideInAPackage("..exception.inconsistency..")
                    .because("InconsistencyException объявляется в exception.inconsistency.")
                    .check(classes);
            classes()
                    .that().haveFullyQualifiedName(IntegrationException.class.getName())
                    .should().resideInAPackage("..exception.integration..")
                    .because("IntegrationException объявляется в exception.integration.")
                    .check(classes);
            classes()
                    .that().haveFullyQualifiedName(DataSourceException.class.getName())
                    .should().resideInAPackage("..exception.datasource..")
                    .because("DataSourceException объявляется в exception.datasource.")
                    .check(classes);
            classes()
                    .that().haveFullyQualifiedName(BusinessException.class.getName())
                    .should().resideInAPackage("..exception.business..")
                    .because("BusinessException объявляется в exception.business.")
                    .check(classes);
        }

        @Test
        void httpMarkerInterfaces_liveOnlyInExceptionHttpstatus() {
            for (Class<?> marker : HTTP_MARKERS) {
                classes()
                        .that().haveFullyQualifiedName(marker.getName())
                        .should().resideInAPackage("..exception.httpstatus..")
                        .because("HTTP-маркеры объявляются только в exception.httpstatus (cross-cutting ось).")
                        .check(classes);
            }
        }

        @Test
        void concreteInBucket_implementsLocalSemanticMarker() {
            classes()
                    .that().resideInAPackage("..exception.validation..")
                    .and().areNotInterfaces()
                    .should().beAssignableTo(ValidationException.class)
                    .because("Каждый concrete-класс в validation/ реализует ValidationException.")
                    .check(classes);
            classes()
                    .that().resideInAPackage("..exception.inconsistency..")
                    .and().areNotInterfaces()
                    .should().beAssignableTo(InconsistencyException.class)
                    .because("Каждый concrete-класс в inconsistency/ реализует InconsistencyException.")
                    .check(classes);
            classes()
                    .that().resideInAPackage("..exception.integration..")
                    .and().areNotInterfaces()
                    .should().beAssignableTo(IntegrationException.class)
                    .because("Каждый concrete-класс в integration/ реализует IntegrationException.")
                    .check(classes);
            classes()
                    .that().resideInAPackage("..exception.datasource..")
                    .and().areNotInterfaces()
                    .should().beAssignableTo(DataSourceException.class)
                    .because("Каждый concrete-класс в datasource/ реализует DataSourceException.")
                    .check(classes);
        }

        @Test
        void markerInterfaces_haveNoInstanceMethods() {
            ArchCondition<JavaClass> noInstanceMethods = new ArchCondition<>(
                    "have no non-static interface methods (marker interfaces are empty)") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean hasInstance = item.getMethods().stream()
                            .anyMatch(m -> !m.getModifiers().contains(JavaModifier.STATIC));
                    if (hasInstance) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " must remain a pure marker (no instance methods)."));
                    }
                }
            };
            classes()
                    .that().resideInAnyPackage(
                            "..exception.httpstatus..",
                            "..exception.validation..",
                            "..exception.inconsistency..",
                            "..exception.integration..",
                            "..exception.datasource..",
                            "..exception.business..")
                    .and().areInterfaces()
                    .should(noInstanceMethods)
                    .because("Маркер-интерфейсы без методов (трёхосная модель).")
                    .check(classes);
        }

        @Test
        void noMarkersLegacySubpackage() {
            ArchCondition<JavaClass> forbidden = new ArchCondition<>("not reside in removed exception.markers") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " — пакет exception.markers удалён (anti-regression)."));
                }
            };
            classes()
                    .that().resideInAPackage("..exception.markers..")
                    .should(forbidden)
                    .allowEmptyShould(true)
                    .because("Старый markers/semantic и markers/http перенесены в bucket-пакеты и exception.httpstatus.")
                    .check(classes);
        }

        @Test
        void concreteOutsideInternal_hasExactlyOneSemanticMarker() {
            ArchCondition<JavaClass> exactlyOneSemantic = new ArchCondition<>(
                    "implement exactly one semantic marker interface") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    long n = SEMANTIC_MARKERS.stream().filter(item::isAssignableTo).count();
                    if (n != 1) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " must implement exactly one semantic marker, found " + n));
                    }
                }
            };
            classes()
                    .that().resideInAnyPackage(
                            "..exception.validation..",
                            "..exception.inconsistency..",
                            "..exception.integration..",
                            "..exception.datasource..")
                    .and().areNotInterfaces()
                    .should(exactlyOneSemantic)
                    .because("Правило 4a: вне internal/ — ровно один семантический маркер.")
                    .check(classes);
        }

        @Test
        void concreteInsideInternal_hasNoSemanticMarkers() {
            ArchCondition<JavaClass> noSemantic = new ArchCondition<>(
                    "implement no semantic marker interfaces") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean any = SEMANTIC_MARKERS.stream().anyMatch(item::isAssignableTo);
                    if (any) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " must not implement semantic markers (internal/ package)."));
                    }
                }
            };
            classes()
                    .that().resideInAPackage("..exception.internal..")
                    .and().areNotInterfaces()
                    .should(noSemantic)
                    .because("Правило 4b: в internal/ — без семантических маркеров.")
                    .check(classes);
        }

        @Test
        void concreteLeaves_implementAtMostOneHttpMarker() {
            ArchCondition<JavaClass> atMostOneHttp = new ArchCondition<>(
                    "implement at most one HTTP marker interface") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    long n = HTTP_MARKERS.stream().filter(item::isAssignableTo).count();
                    if (n > 1) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " must implement at most one HTTP marker, found " + n));
                    }
                }
            };
            classes()
                    .that().resideInAnyPackage(
                            "..exception.validation..",
                            "..exception.inconsistency..",
                            "..exception.integration..",
                            "..exception.datasource..",
                            "..exception.internal..")
                    .and().areNotInterfaces()
                    .should(atMostOneHttp)
                    .because("Правило 5: не более одного HTTP-маркера на concrete-класс.")
                    .check(classes);
        }

        @Test
        void concreteLeaves_areFinalExceptExternalServiceException() {
            classes()
                    .that().resideInAnyPackage(
                            "..exception.validation..",
                            "..exception.inconsistency..",
                            "..exception.datasource..",
                            "..exception.internal..")
                    .and().areNotInterfaces()
                    .and().doNotHaveModifier(JavaModifier.ABSTRACT)
                    .should().haveModifier(JavaModifier.FINAL)
                    .because("Concrete-листья — final (кроме ExternalServiceException в integration/); абстрактные корни bucket'ов не проверяются.")
                    .check(classes);
            classes()
                    .that().resideInAPackage("..exception.integration..")
                    .and().areNotInterfaces()
                    .and().doNotHaveModifier(JavaModifier.ABSTRACT)
                    .and(DescribedPredicate.describe("simple name is not ExternalServiceException",
                            jc -> !"ExternalServiceException".equals(jc.getSimpleName())))
                    .should().haveModifier(JavaModifier.FINAL)
                    .because("В integration/ единственный non-final — базовый ExternalServiceException.")
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("Package dependencies")
    class PackageDeps {

        @Test
        void modelPackage_doesNotDependOnExceptionPackage() {
            noClasses()
                    .that().resideInAPackage("..model..")
                    .should().dependOnClassesThat().resideInAPackage("..exception..")
                    .because("model package must not depend on exception package")
                    .check(classes);
        }

        @Test
        void utilsPackage_doesNotDependOnExceptionPackage() {
            noClasses()
                    .that().resideInAPackage("..utils..")
                    .should().dependOnClassesThat().resideInAPackage("..exception..")
                    .because("utils package must not depend on exception package")
                    .check(classes);
        }

        /**
         * <b>Что проверяет правило.</b> Запрещает существование классов в пакете
         * {@code space.br1440.platform.errorhandling.error_codes}. Пакет был удалён
         * как micropackage-антипаттерн (один класс {@code CanonicalErrorCodes} в собственном
         * пакете) и для выравнивания naming-конвенции (snake_case верхнеуровневый пакет
         * выбивался из {@code utils}/{@code model}/{@code exception}).
         *
         * <p><b>Зачем правило обязательно.</b> Без него ничто не помешает через несколько
         * месяцев добавить {@code error_codes/SomeNewCode.java} -- регрессия пройдёт незамеченной.
         * {@link CanonicalErrors} живёт
         * в {@code utils/}, новые wire-codes должны добавляться туда же.
         *
         * <p><b>Почему не {@code beTopLevelClasses()}.</b> Это разрешение, а не запрет
         * (метод проверяет, что классы не вложенные). Корректная реализация --
         * через {@link ArchCondition}, всегда выдающий violation: любой класс в пакете
         * автоматически означает нарушение. {@code allowEmptyShould(true)} обязателен:
         * после переезда пакет пуст; без флага ArchUnit при пустом наборе бросит
         * "no classes" (поведение ArchUnit 1.x).
         */
        @Test
        void noErrorCodesTopLevelPackage() {
            ArchCondition<JavaClass> notExist = new ArchCondition<>(
                    "not exist (package error_codes was removed as micropackage-antipattern)") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " resides in forbidden package "
                                    + "'space.br1440.platform.errorhandling.error_codes': "
                                    + "CanonicalErrorCodes lives in utils/; the top-level "
                                    + "error_codes package was removed for naming-convention "
                                    + "alignment and to avoid micropackage-antipattern."));
                }
            };

            classes()
                    .that().resideInAPackage("..errorhandling.error_codes..")
                    .should(notExist)
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("ErrorDetail sealed hierarchy")
    class ErrorDetailHierarchy {

        @Test
        void allErrorDetailImplementations_areInModelPackage() {
            classes()
                    .that().implement(ErrorDetail.class)
                    .should().resideInAPackage("..model.error_detail..")
                    .because("all ErrorDetail implementations should live in model.error_detail package")
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("Dependency cycles")
    class DependencyCycles {

        @Test
        void noCyclesBetweenTopLevelPackages() {
            slices().matching("space.br1440.platform.errorhandling.(*)..")
                    .should().beFreeOfCycles()
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("Naming conventions in utils package")
    class UtilsNaming {

        /**
         * После рефакторинга {@code ExtraBuilder -> ClientMetadataBuilder} ни в одном классе
         * пакета {@code utils} не должно остаться следов термина {@code Extra}.
         * Терминология выровнена с DTO-полями {@code clientMetadata}/{@code diagnosticMetadata}.
         */
        @Test
        void noExtraNamedClassesInUtils() {
            noClasses()
                    .that().resideInAPackage("..errorhandling.utils..")
                    .should().haveSimpleNameContaining("Extra")
                    .because("термин Extra устарел: используйте ClientMetadataBuilder / "
                            + "DiagnosticMetadataBuilder. Имена классов в utils/ должны отражать "
                            + "роль метаданных в API-контракте.")
                    .check(classes);
        }

        /**
         * Защита от регрессии: метод {@code wrapInQuotes} в {@code StringUtils} оборачивал
         * строки в одинарные кавычки в DTO-пути, ломая контракт значений {@code clientMetadata}
         * (голая строка без обрамляющих {@code '}). Возвращение этого утиль-метода или его
         * аналога запрещено архитектурно.
         */
        @Test
        void noWrapInQuotesMethodAnywhere() {
            ArchCondition<JavaClass> notDeclareWrapInQuotes = new ArchCondition<>(
                    "not declare a wrapInQuotes(...) method") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    item.getMethods().stream()
                            .filter(m -> "wrapInQuotes".equals(m.getName()))
                            .forEach(m -> events.add(SimpleConditionEvent.violated(item,
                                    item.getFullName() + " declares forbidden method wrapInQuotes(): "
                                            + "values in clientMetadata must be plain strings "
                                            + "without surrounding quotes (DTO contract).")));
                }
            };

            classes()
                    .that().resideInAPackage("..errorhandling..")
                    .should(notDeclareWrapInQuotes)
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("Mapping package -- single public facade")
    class MappingFacade {

        /**
         * <b>Что проверяет правило.</b> В пакете
         * {@code space.br1440.platform.errorhandling.model.mapping} должен быть ровно
         * один {@code public} тип -- единый фасад
         * {@link space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper}.
         * Все остальные классы (например, {@code ErrorDetailToDtoMapper}) -- package-private
         * детали реализации.
         *
         * <p><b>Зачем правило обязательно.</b> До этого рефакторинга процесс был размазан
         * по 4 публичным классам в 3 пакетах ({@code converter/}, {@code error_detail/converter/},
         * {@code assembler/} + {@code ErrorEntryInvariants}) -- разработчику приходилось
         * открывать 4 файла, чтобы понять контракт сборки DTO. Теперь весь pipeline читается
         * сверху вниз в одном фасаде. Если кто-то поднимет видимость
         * package-private хелпера до {@code public} -- регрессия будет мгновенно поймана здесь.
         *
         * <p><b>Почему именно {@code mapping}, а не "..mapping..".</b> Подпакеты пока
         * не предполагаются; если они появятся, инвариант должен быть пересмотрен явно
         * (с обновлением Javadoc), а не молча обойдён.
         */
        @Test
        void mappingPackage_hasOnlyOnePublicClass() {
            ArchCondition<JavaClass> notBePublic = new ArchCondition<>(
                    "be package-private (mapping package exposes exactly one public facade: "
                            + "ErrorEntryToDtoMapper)") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getModifiers().contains(JavaModifier.PUBLIC)) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " is public, but the mapping package "
                                        + "must expose exactly one public type (ErrorEntryToDtoMapper). "
                                        + "Make this class package-private or move it elsewhere."));
                    }
                }
            };

            classes()
                    .that().resideInAPackage("space.br1440.platform.errorhandling.model.mapping")
                    .and().haveSimpleNameNotEndingWith("ErrorEntryToDtoMapper")
                    .should(notBePublic)
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("Constant-interface antipattern (Effective Java item 22)")
    class ConstantInterface {

        /**
         * <b>Что проверяет правило.</b> Во всём пакете {@code ..errorhandling..} нельзя
         * объявлять интерфейс, всё содержимое которого -- {@code public static final}-константы
         * (Effective Java, item 22 -- "Use interfaces only to define types"). Альтернатива --
         * {@code final}-класс с {@code private}-конструктором (или {@code @UtilityClass}).
         *
         * <p>Маркер-интерфейсы в {@code exception.httpstatus} и bucket-пакетах не попадают под антипаттерн,
         * так как не состоят только из {@code public static final}-полей.
         */
        @Test
        void noConstantInterfaces() {
            ArchCondition<JavaClass> notBeConstantInterface = new ArchCondition<>(
                    "not be a constant interface (only public static final fields, no methods)") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (!item.isInterface()) {
                        return;
                    }
                    if (item.getMethods().stream().anyMatch(m -> !m.getModifiers().contains(JavaModifier.STATIC))) {
                        return;
                    }
                    boolean hasPublicStaticFinalFields = item.getFields().stream()
                            .anyMatch(ConstantInterface::isPublicStaticFinal);
                    if (hasPublicStaticFinalFields) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " is a constant interface (Effective Java item 22): "
                                        + "use a final class with a private constructor instead"));
                    }
                }
            };

            classes()
                    .that().resideInAPackage("..errorhandling..")
                    .should(notBeConstantInterface)
                    .check(classes);
        }

        private static boolean isPublicStaticFinal(JavaField field) {
            var mods = field.getModifiers();
            return mods.contains(JavaModifier.PUBLIC)
                    && mods.contains(JavaModifier.STATIC)
                    && mods.contains(JavaModifier.FINAL);
        }
    }

    // Бывший nested-класс DomainHolderEncapsulation удалён вместе с PlatformErrorDomainHolder:
    // имя сервиса теперь резолвится через ErrorEntryFactory.resolveDomain() из Supplier<String>,
    // поставляемого Tracing-стартером, а не из глобального write-once Holder'а.
}
