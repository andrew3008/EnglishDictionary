package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClassUtils")
class ClassUtilsTest {

    @Test
    void isPresentTrueForClasspathClass() {
        // when / then
        assertThat(ClassUtils.isPresent("java.lang.Void", null)).isTrue();
    }

    @Test
    void isPresentFalseForMissingClass() {
        // when / then
        assertThat(ClassUtils.isPresent("space.br1440.platform.errorhandling.MissingTypeZ", null)).isFalse();
    }

    @Test
    void isInstanceOf() {
        // when / then
        assertThat(ClassUtils.isInstanceOf(Number.class, 3)).isTrue();
        assertThat(ClassUtils.isInstanceOf(Number.class, "x")).isFalse();
        assertThat(ClassUtils.isInstanceOf(absentClass(), "x")).isFalse();
    }

    private Class<?> absentClass() {
        return null;
    }

}
