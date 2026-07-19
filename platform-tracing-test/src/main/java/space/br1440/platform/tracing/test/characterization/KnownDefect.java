package space.br1440.platform.tracing.test.characterization;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает зелёный тест, фиксирующий текущее дефектное поведение до owner-слайса.
 */
@Tag("known-defect")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KnownDefect {

    KnownDefectId value();
}
