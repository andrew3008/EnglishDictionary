package space.br1440.platform.errorhandling.core.security;

import lombok.experimental.UtilityClass;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Маркер-singleton для per-chain opt-out из автоматической регистрации
 * {@code SecurityProblemSupport} в {@link HttpSecurity}.
 *
 * <p>В сценариях с несколькими {@link HttpSecurity}-цепочками (например, REST API + form-login UI)
 * бывает нужно сохранить дефолтный whitelabel/redirect-flow для одной
 * из цепочек, не отключая авто-регистрацию глобально.
 *
 * <h2>Использование</h2>
 * <pre>{@code
 * @Bean
 * public SecurityFilterChain uiSecurityChain(HttpSecurity http) throws Exception {
 *     http.setSharedObject(SkipProblemSupport.class, SkipProblemSupport.INSTANCE);
 *     return http
 *         .securityMatcher("/admin/**")
 *         .formLogin(Customizer.withDefaults())
 *         .build();
 * }
 * }</pre>
 *
 * <p>{@code ProblemSecurityBeanPostProcessor} проверяет наличие этого shared-object
 * (любое не-{@code null} значение типа {@code SkipProblemSupport}) и пропускает
 * помеченные цепочки. Помеченная цепочка не будет преобразовывать 401/403 в
 * {@code application/problem+json}, сохраняя стандартное поведение Spring Security.
 *
 * <h2>Альтернативы</h2>
 * <ul>
 *   <li>{@code platform.error-handling.security.auto-register-mode=NONE} — глобальное отключение;
 *       подключение возможно вручную через {@code ProblemSupportConfigurer}.</li>
 *   <li>Прямой override через {@code http.exceptionHandling(custom)} ПОСЛЕ авторегистрации —
 *       последний выигрывает (Spring Security applies-order).</li>
 * </ul>
 */
@UtilityClass
public final class SkipProblemSupport {

    public static final SkipProblemSupport INSTANCE = new SkipProblemSupport();

}
