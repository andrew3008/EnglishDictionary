package space.br1440.platform.errorhandling.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.util.ClassUtils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link BeanPostProcessor}, который <b>автоматически</b> регистрирует
 * {@link SecurityProblemSupport} во все прототипные бины {@link HttpSecurity},
 * создаваемые {@code HttpSecurityConfiguration}.
 *
 * <p>
 * Без этого BeanPostProcessor пользователь <b>обязан</b> вручную подключить
 * {@link SecurityProblemSupport} в каждую {@code SecurityFilterChain}:
 * <pre>{@code
 * http.with(problemSupportConfigurer, Customizer.withDefaults())
 * }</pre>
 * Если пользователь забудет это сделать, Security-ошибки (401, 403) вернутся
 * в стандартном формате Spring Boot (HTML Whitelabel или JSON без {@code ErrorEntryDTO}),
 * нарушая контракт API.
 *
 * <h2>Как работает</h2>
 * <ol>
 *   <li>{@code HttpSecurityConfiguration} создаёт {@link HttpSecurity} как
 *       <b>prototype</b>-бин.</li>
 *   <li>{@code postProcessAfterInitialization} перехватывает каждый экземпляр
 *       и вызывает {@code http.exceptionHandling()} с нашим
 *       {@link SecurityProblemSupport} как {@code AuthenticationEntryPoint}
 *       и {@code AccessDeniedHandler}.</li>
 *   <li>Далее бин инжектируется в {@code @Bean SecurityFilterChain} метод
 *       пользователя, где он может <b>переопределить</b> наши настройки,
 *       если явно вызовет {@code http.exceptionHandling(...)}.</li>
 * </ol>
 *
 * <h2>Порядок приоритетов</h2>
 * <pre>
 *   BeanPostProcessor (мы)    → default AuthenticationEntryPoint / AccessDeniedHandler
 *        │
 *        ▼
 *   @Bean SecurityFilterChain (пользователь)  → может переопределить
 *        │
 *        ▼
 *   http.build()
 * </pre>
 * <p>
 * Наша конфигурация применяется <b>до</b> пользовательского кода (prototype bean
 * проходит через {@code BeanPostProcessor} при создании, до инъекции).
 * Если пользователь явно настраивает {@code exceptionHandling},
 * его конфигурация <b>перезаписывает</b> нашу — это корректное поведение.
 *
 * <h2>Отключение</h2>
 * <p>
 * Управляется свойством {@code platform.error-handling.security.auto-register-mode}
 * (см. {@code SecurityMvcAutoConfiguration} и {@code ErrorHandlingProperties.Security}):
 * <ul>
 *   <li>{@code ALL} (по умолчанию) — авто-регистрация включена для всех
 *       {@link org.springframework.security.config.annotation.web.builders.HttpSecurity}-цепочек</li>
 *   <li>{@code NONE} — авто-регистрация отключена; пользователь обязан подключить
 *       {@link SecurityProblemSupport} самостоятельно через {@link ProblemSupportConfigurer}
 *       или явную конфигурацию {@code http.exceptionHandling(...)}</li>
 * </ul>
 *
 * <h2>Потоко-безопасность</h2>
 * <p>
 * Класс потокобезопасен: {@code ObjectProvider} лениво резолвит {@link SecurityProblemSupport},
 * а {@code AtomicInteger} обеспечивает корректный подсчёт обработанных бинов.
 */
@Slf4j
public class ProblemSecurityBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<SecurityProblemSupport> securityProblemSupportProvider;
    private final AtomicInteger configuredCount = new AtomicInteger(0);

    public ProblemSecurityBeanPostProcessor(ObjectProvider<SecurityProblemSupport> securityProblemSupportProvider) {
        this.securityProblemSupportProvider = securityProblemSupportProvider;
    }

    @Override
    @Nullable
    public Object postProcessAfterInitialization(@NonNull Object bean,
                                                 @NonNull String beanName) throws BeansException {
        if (!ClassUtils.isAssignableValue(HttpSecurity.class, bean)) {
            return bean;
        }

        HttpSecurity http = (HttpSecurity) bean;

        SkipProblemSupport skip = http.getSharedObject(SkipProblemSupport.class);
        if (skip != null) {
            log.debug("HttpSecurity bean '{}' marked as SkipProblemSupport — пропускаем авто-регистрацию", beanName);
            return bean;
        }

        securityProblemSupportProvider.ifAvailable(support -> {
            try {
                http.exceptionHandling(handling -> handling
                        .authenticationEntryPoint(support)
                        .accessDeniedHandler(support));

                int count = configuredCount.incrementAndGet();
                log.debug("Auto-registered SecurityProblemSupport in HttpSecurity bean '{}' (total configured: {})", beanName, count);
            } catch (Exception e) {
                log.warn("Failed to auto-register SecurityProblemSupport in HttpSecurity bean '{}': {}. " +
                         "Security exceptions may not be formatted as application/problem+json. " +
                         "Configure manually via ProblemSupportConfigurer or http.exceptionHandling().",
                        beanName, e.getMessage());
            }
        });

        return bean;
    }
}
