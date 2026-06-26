package space.br1440.platform.datasource.support.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Управляет жизненным циклом PostgreSQL-кластера в {@link PgClusterExtension}.
 *
 * <ul>
 *   <li>{@link Mode#SHARED} (default) — один ref-counted кластер на JVM для всех
 *       «обычных» IT; контейнеры с {@code withReuse(true)} переживают повторные
 *       {@code integrationTest} прогоны.</li>
 *   <li>{@link Mode#ISOLATED} — отдельный кластер на тест-класс, всегда
 *       {@code withReuse(false)} и полный teardown в {@code @AfterAll}. Обязателен
 *       для chaos / topology-mutating сценариев ({@code pg_promote}, Toxiproxy).</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PgClusterScope {

    Mode value() default Mode.SHARED;

    enum Mode {
        SHARED,
        ISOLATED
    }
}
