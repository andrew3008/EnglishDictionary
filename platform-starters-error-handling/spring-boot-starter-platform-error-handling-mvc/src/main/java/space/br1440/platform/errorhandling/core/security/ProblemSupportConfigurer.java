package space.br1440.platform.errorhandling.core.security;

import org.springframework.lang.NonNull;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;

/**
 * Spring Security конфигуратор для декларативного подключения {@link SecurityProblemSupport}.
 *
 * <p>
 * Упрощает интеграцию {@link SecurityProblemSupport} с Spring Security 6.x,
 * позволяя подключить единый формат ошибок одной строкой через
 * {@code HttpSecurity.with(configurer)}.
 *
 * <h2>Применение — рекомендуемый способ (Spring Security 6.2+)</h2>
 * <pre>{@code
 * @Bean
 * public SecurityFilterChain securityFilterChain(
 *         HttpSecurity http,
 *         ProblemSupportConfigurer problemSupportConfigurer) throws Exception {
 *
 *     return http
 *         .with(problemSupportConfigurer, Customizer.withDefaults())
 *         .authorizeHttpRequests(auth -> auth
 *             .requestMatchers("/public/**").permitAll()
 *             .anyRequest().authenticated())
 *         .build();
 * }
 * }</pre>
 *
 * <h2>Альтернативный способ (явная конфигурация)</h2>
 * <p>
 * Если нужен полный контроль над {@code exceptionHandling}:
 * <pre>{@code
 * @Bean
 * public SecurityFilterChain securityFilterChain(HttpSecurity http,
 *                                                SecurityProblemSupport problemSupport) throws Exception {
 *     return http
 *         .exceptionHandling(ex -> ex
 *             .authenticationEntryPoint(problemSupport)
 *             .accessDeniedHandler(problemSupport))
 *         .build();
 * }
 * }</pre>
 *
 * <h2>Что делает конфигуратор</h2>
 * <p>
 * В методе {@link #init(HttpSecurity)} регистрирует {@link SecurityProblemSupport}
 * как {@code AuthenticationEntryPoint} и {@code AccessDeniedHandler} через {@link ExceptionHandlingConfigurer}.
 * Это перенаправляет все security-ошибки фильтрового уровня в {@code GlobalExceptionHandler} для формирования
 * единого {@code ErrorEntryDTO} с {@code Content-Type: application/problem+json}.
 */
public final class ProblemSupportConfigurer extends AbstractHttpConfigurer<ProblemSupportConfigurer, HttpSecurity> {

    private final SecurityProblemSupport problemSupport;

    public ProblemSupportConfigurer(@NonNull SecurityProblemSupport problemSupport) {
        this.problemSupport = problemSupport;
    }

    @Override
    public void init(@NonNull HttpSecurity http) throws Exception {
        http.exceptionHandling(handling -> handling
                .authenticationEntryPoint(problemSupport)
                .accessDeniedHandler(problemSupport));
    }
}
