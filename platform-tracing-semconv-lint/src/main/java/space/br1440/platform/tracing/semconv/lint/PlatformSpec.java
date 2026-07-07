package space.br1440.platform.tracing.semconv.lint;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.semconv.lint.rules.AttributeRule;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Фабрика встроенных правил платформенного стандарта семантических конвенций.
 * <p>
 * Соответствует разделу §68 платформенного документа (обязательные атрибуты span'ов) и
 * нормализованному набору значений из {@link SpanCategory} / {@link SpanResult}.
 * <p>
 * Класс может использоваться двумя способами:
 * <ol>
 *   <li>{@link #defaultRules()} — список правил по умолчанию;</li>
 *   <li>{@link #defaultLinter()} — готовый {@link Linter} с зарегистрированными правилами.</li>
 * </ol>
 */
public final class PlatformSpec {

    private PlatformSpec() {
        // utility
    }

    public static List<LintRule> defaultRules() {
        Set<String> allowedTypes = Arrays.stream(SpanCategory.values())
                .map(SpanCategory::value)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> allowedResults = Arrays.stream(SpanResult.values())
                .map(SpanResult::value)
                .collect(Collectors.toUnmodifiableSet());
        // OTel semconv well-known values для deployment.environment.name (Фаза 9, PR-8).
        // Старые алиасы dev/stage/prod нормализуются EnvironmentNormalizer'ом до этих значений.
        Set<String> allowedEnvironments = Set.of("development", "staging", "production", "test", "unknown");
        // Source of truth — PlatformSamplingReasons (Фаза 16): на экспортируемых span'ах
        // могут встречаться только EXPORTED-значения (DROP-значения не покидают процесс).
        // До Фазы 16 здесь был рукописный список с дрейфом (qa_header/ratio/parent).
        Set<String> allowedSamplingReasons =
                space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons.EXPORTED;

        return List.of(
                AttributeRule.builder("PLATFORM_SERVICE_NAME_REQUIRED", PlatformAttributes.SERVICE_NAME)
                        .description("Атрибут service.name обязателен (обычно из ResourceProvider).")
                        .severity(LintSeverity.ERROR)
                        .required()
                        .build(),

                AttributeRule.builder("PLATFORM_C_GROUP_REQUIRED", PlatformAttributes.PLATFORM_C_GROUP)
                        .description("Атрибут platform.c_group обязателен (организационная группа сервиса).")
                        .severity(LintSeverity.ERROR)
                        .required()
                        .build(),

                AttributeRule.builder("PLATFORM_ID_REQUIRED", PlatformAttributes.PLATFORM_ID)
                        .description("Атрибут platform.id обязателен (идентификатор сервиса в реестре платформы).")
                        .severity(LintSeverity.ERROR)
                        .required()
                        .build(),

                AttributeRule.builder("PLATFORM_TYPE_REQUIRED", PlatformAttributes.PLATFORM_TYPE)
                        .description("Атрибут platform.trace.type должен присутствовать и иметь стандартное значение.")
                        .severity(LintSeverity.ERROR)
                        .required()
                        .allowedValues(allowedTypes)
                        .build(),

                AttributeRule.builder("PLATFORM_RESULT_REQUIRED", PlatformAttributes.PLATFORM_RESULT)
                        .description("Атрибут platform.trace.result должен присутствовать и иметь допустимое значение.")
                        .severity(LintSeverity.ERROR)
                        .required()
                        .allowedValues(allowedResults)
                        .build(),

                AttributeRule.builder("PLATFORM_ENVIRONMENT_ALLOWED", PlatformAttributes.PLATFORM_ENVIRONMENT)
                        .description("Атрибут deployment.environment.name должен быть одним из стандартных значений.")
                        .severity(LintSeverity.WARNING)
                        .allowedValues(allowedEnvironments)
                        .build(),

                AttributeRule.builder("PLATFORM_SAMPLING_REASON_ALLOWED", PlatformAttributes.PLATFORM_SAMPLING_REASON)
                        .description("Атрибут platform.sampling.reason при наличии должен иметь каноническое значение.")
                        .severity(LintSeverity.WARNING)
                        .allowedValues(allowedSamplingReasons)
                        .build(),

                AttributeRule.builder("HTTP_SERVER_METHOD_REQUIRED", PlatformAttributes.HTTP_REQUEST_METHOD)
                        .description("HTTP server span обязан содержать http.request.method.")
                        .severity(LintSeverity.ERROR)
                        .required()
                        .applicableKind("SERVER")
                        .build(),

                AttributeRule.builder("HTTP_SERVER_STATUS_REQUIRED", PlatformAttributes.HTTP_RESPONSE_STATUS_CODE)
                        .description("HTTP server span обязан содержать http.response.status_code.")
                        .severity(LintSeverity.ERROR)
                        .required()
                        .applicableKind("SERVER")
                        .build()
        );
    }

    public static Linter defaultLinter() {
        return new Linter(defaultRules());
    }
}
