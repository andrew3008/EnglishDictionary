package space.br1440.platform.tracing.test.semconv;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.api.semconv.ValidationMode;

/**
 * Test-конфигурация, переводящая семантическую валидацию в режим {@link ValidationMode#STRICT}
 * для интеграционных/слайс-тестов прикладного кода.
 * <p>
 * Принцип: в проде дефолт — {@code WARN} (нарушение семконвенций не должно ронять трафик),
 * а в test/CI — {@code STRICT} (нарушение обязано падать на сборке, чтобы semantic drift не доехал
 * до прода). Бин {@link ValidationMode} имеет приоритет над property и подхватывается
 * {@code SemanticLayerAutoConfiguration}.
 * <p>
 * Включена по умолчанию ({@code matchIfMissing = true}); отключается флагом
 * {@code platform.tracing.test.semconv-strict=false} для точечных тестов, где нужно проверить
 * именно WARN/DISABLED-поведение.
 *
 * <p>Подключение в тесте:
 * <pre>{@code
 * @SpringBootTest
 * @Import(SemconvStrictTestAutoConfiguration.class)
 * class MyServiceTracingTest { ... }
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.tracing.test.semconv-strict", matchIfMissing = true)
public class SemconvStrictTestAutoConfiguration {

    /** Форсирует STRICT-режим семантической валидации в тестовом контексте. */
    @Bean
    public ValidationMode platformTestSemconvValidationMode() {
        return ValidationMode.STRICT;
    }
}
