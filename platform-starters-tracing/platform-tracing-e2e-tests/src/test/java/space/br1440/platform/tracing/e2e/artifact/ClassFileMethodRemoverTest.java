package space.br1440.platform.tracing.e2e.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

import org.junit.jupiter.api.Test;

class ClassFileMethodRemoverTest {

    private static final String TARGET_METHOD = "sdkDefiningLoader";
    private static final String TARGET_METHOD_DESCRIPTOR = "()Ljava/lang/ClassLoader;";

    @Test
    void removedMethodDisappearsAndTheRemainingClassStaysVerifiableAndLoadable() throws Exception {
        // given
        byte[] original = readFixtureBytes();

        // when
        byte[] patched = ClassFileMethodRemover.removeMethod(
                original, TARGET_METHOD, TARGET_METHOD_DESCRIPTOR);

        // then
        Class<?> patchedClass = defineClass(patched);
        assertThat(patchedClass.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain(TARGET_METHOD);
        assertThat(invokeKeep(patchedClass)).isEqualTo("keep");
    }

    @Test
    void missingTargetMethodIsRejected() throws IOException {
        // given
        byte[] original = readFixtureBytes();

        // when
        // then
        assertThatThrownBy(() -> ClassFileMethodRemover.removeMethod(
                original, "doesNotExist", TARGET_METHOD_DESCRIPTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target method is missing");
    }

    @Test
    void inputWithoutClassFileMagicIsRejected() {
        // given
        byte[] notAClassFile = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        // when
        // then
        assertThatThrownBy(() -> ClassFileMethodRemover.removeMethod(
                notAClassFile, TARGET_METHOD, TARGET_METHOD_DESCRIPTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a class file");
    }

    private static byte[] readFixtureBytes() throws IOException {
        String resourceName = ClassFileMethodRemoverTest.class.getSimpleName() + "$" + Fixture.class.getSimpleName() + ".class";
        try (InputStream input = Objects.requireNonNull(
                Fixture.class.getResourceAsStream(resourceName),
                "compiled fixture resource is missing: " + resourceName)) {
            return input.readAllBytes();
        }
    }

    private static Class<?> defineClass(byte[] classFile) throws ClassNotFoundException {
        ClassLoader loader = new ClassLoader(ClassFileMethodRemoverTest.class.getClassLoader()) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(Fixture.class.getName())) {
                    return super.loadClass(name, resolve);
                }

                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, classFile, 0, classFile.length);
                }

                if (resolve) {
                    resolveClass(loaded);
                }

                return loaded;
            }
        };

        return Class.forName(Fixture.class.getName(), true, loader);
    }

    private static Object invokeKeep(Class<?> patchedClass) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        Constructor<?> constructor = patchedClass.getDeclaredConstructor();
        constructor.setAccessible(true);

        Object instance = constructor.newInstance();
        Method method = patchedClass.getDeclaredMethod("keep");
        method.setAccessible(true);
        return method.invoke(instance);
    }

    static final class Fixture {

        // L и D намеренно не читаются: их единственная цель — заставить компилятор
        // положить в constant pool записи тегов 5 (long) и 6 (double), которые
        // parseConstantPool в ClassFileMethodRemover разбирает как двойной по ширине слот.
        @SuppressWarnings("unused")
        static final long L = 123456789012L;
        @SuppressWarnings("unused")
        static final double D = 1.5d;

        @SuppressWarnings("unused")
        ClassLoader sdkDefiningLoader() {
            return null;
        }

        @SuppressWarnings("unused")
        String keep() {
            return "keep";
        }
    }
}
