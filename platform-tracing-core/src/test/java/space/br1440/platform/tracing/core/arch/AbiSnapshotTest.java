package space.br1440.platform.tracing.core.arch;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Фиксирует публичный ABI модулей API и core до начала структурных слайсов.
 */
class AbiSnapshotTest {

    private static final String SNAPSHOT_RESOURCE = "/abi/platform-tracing-api-core.txt";
    private static final Path ACTUAL_REPORT = Path.of("build", "reports", "abi", "platform-tracing-api-core.txt");

    @Test
    void publicAbiMatchesApprovedSnapshot() throws Exception {
        String actual = renderSnapshot();
        writeReport(actual);

        try (InputStream input = AbiSnapshotTest.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            assertThat(input)
                    .as("ABI baseline отсутствует; actual записан в %s", ACTUAL_REPORT)
                    .isNotNull();
            String expected = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            assertThat(actual)
                    .as("Незапланированный ABI delta. Actual: %s", ACTUAL_REPORT)
                    .isEqualTo(expected);
        }
    }

    private static String renderSnapshot() throws Exception {
        Set<String> classNames = new TreeSet<>();
        collectClasses(TraceOperations.class, "space.br1440.platform.tracing.api", classNames);
        collectClasses(DefaultTraceOperations.class, "space.br1440.platform.tracing.core", classNames);

        List<String> lines = new ArrayList<>();
        for (String className : classNames) {
            Class<?> type = Class.forName(className, false, AbiSnapshotTest.class.getClassLoader());
            if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic()) {
                continue;
            }
            renderType(type, lines);
        }
        return String.join("\n", lines) + "\n";
    }

    private static void collectClasses(Class<?> anchor, String packagePrefix, Set<String> target)
            throws Exception {
        URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
        URI uri = location.toURI();
        if ("file".equals(uri.getScheme()) && Files.isDirectory(Path.of(uri))) {
            collectFromDirectory(Path.of(uri), packagePrefix, target);
            return;
        }

        URL classUrl = anchor.getResource('/' + anchor.getName().replace('.', '/') + ".class");
        if (classUrl != null && "jar".equals(classUrl.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) classUrl.openConnection();
            try (JarFile jar = connection.getJarFile()) {
                collectFromJar(jar, packagePrefix, target);
            }
            return;
        }
        throw new IllegalStateException("Неподдерживаемое расположение class-файлов: " + location);
    }

    private static void collectFromDirectory(Path root, String packagePrefix, Set<String> target)
            throws IOException {
        Path packageRoot = root.resolve(packagePrefix.replace('.', '/'));
        try (var paths = Files.walk(packageRoot)) {
            paths.filter(path -> path.toString().endsWith(".class"))
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(name -> name.substring(0, name.length() - 6).replace('\\', '.').replace('/', '.'))
                    .filter(AbiSnapshotTest::isProductionClassName)
                    .forEach(target::add);
        }
    }

    private static void collectFromJar(JarFile jar, String packagePrefix, Set<String> target) {
        String pathPrefix = packagePrefix.replace('.', '/') + '/';
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(pathPrefix) && name.endsWith(".class")) {
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                if (isProductionClassName(className)) {
                    target.add(className);
                }
            }
        }
    }

    private static boolean isProductionClassName(String name) {
        return !name.endsWith("module-info")
                && !name.endsWith("package-info")
                && !name.matches(".*\\$\\d+.*");
    }

    private static void renderType(Class<?> type, List<String> lines) {
        lines.add("TYPE " + typeModifiers(type) + " " + type.getName() + inheritance(type));

        Arrays.stream(type.getDeclaredFields())
                .filter(AbiSnapshotTest::isPublicOrProtected)
                .filter(field -> !field.isSynthetic())
                .map(AbiSnapshotTest::renderField)
                .sorted()
                .forEach(lines::add);

        Arrays.stream(type.getDeclaredConstructors())
                .filter(AbiSnapshotTest::isPublicOrProtected)
                .filter(constructor -> !constructor.isSynthetic())
                .map(AbiSnapshotTest::renderConstructor)
                .sorted()
                .forEach(lines::add);

        Arrays.stream(type.getDeclaredMethods())
                .filter(AbiSnapshotTest::isPublicOrProtected)
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .map(AbiSnapshotTest::renderMethod)
                .sorted()
                .forEach(lines::add);
    }

    private static String typeModifiers(Class<?> type) {
        List<String> parts = new ArrayList<>();
        String modifiers = Modifier.toString(type.getModifiers()).replace(" interface", "");
        if (!modifiers.isBlank()) {
            parts.add(modifiers);
        }
        if (type.isAnnotation()) {
            parts.add("annotation");
        } else if (type.isEnum()) {
            parts.add("enum");
        } else if (type.isRecord()) {
            parts.add("record");
        } else if (type.isInterface()) {
            parts.add("interface");
        } else {
            parts.add("class");
        }
        return String.join(" ", parts);
    }

    private static String inheritance(Class<?> type) {
        List<String> parts = new ArrayList<>();
        Type superclass = type.getGenericSuperclass();
        if (superclass != null && superclass != Object.class && !type.isEnum() && !type.isRecord()) {
            parts.add("extends " + typeName(superclass));
        }
        Type[] interfaces = type.getGenericInterfaces();
        if (interfaces.length > 0) {
            parts.add((type.isInterface() ? "extends " : "implements ")
                    + Arrays.stream(interfaces).map(AbiSnapshotTest::typeName).sorted().reduce((a, b) -> a + "," + b).orElse(""));
        }
        return parts.isEmpty() ? "" : " " + String.join(" ", parts);
    }

    private static String renderField(Field field) {
        return "  FIELD " + Modifier.toString(field.getModifiers()) + " "
                + typeName(field.getGenericType()) + " " + field.getName();
    }

    private static String renderConstructor(Constructor<?> constructor) {
        return "  CTOR " + Modifier.toString(constructor.getModifiers()) + " "
                + constructor.getDeclaringClass().getName() + parameters(constructor.getGenericParameterTypes())
                + exceptions(constructor.getGenericExceptionTypes());
    }

    private static String renderMethod(Method method) {
        String defaultValue = method.getDefaultValue() == null
                ? ""
                : " default=" + renderValue(method.getDefaultValue());
        return "  METHOD " + Modifier.toString(method.getModifiers()) + " "
                + typeName(method.getGenericReturnType()) + " " + method.getName()
                + parameters(method.getGenericParameterTypes())
                + exceptions(method.getGenericExceptionTypes()) + defaultValue;
    }

    private static String parameters(Type[] types) {
        return "(" + Arrays.stream(types).map(AbiSnapshotTest::typeName)
                .reduce((a, b) -> a + "," + b).orElse("") + ")";
    }

    private static String exceptions(Type[] types) {
        if (types.length == 0) {
            return "";
        }
        return " throws " + Arrays.stream(types).map(AbiSnapshotTest::typeName).sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String typeName(Type type) {
        return type.getTypeName().replace(" ", "");
    }

    private static String renderValue(Object value) {
        if (!value.getClass().isArray()) {
            return String.valueOf(value);
        }
        List<String> elements = new ArrayList<>();
        for (int index = 0; index < Array.getLength(value); index++) {
            elements.add(renderValue(Array.get(value, index)));
        }
        return "[" + String.join(",", elements) + "]";
    }

    private static boolean isPublicOrProtected(Member member) {
        int modifiers = member.getModifiers();
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void writeReport(String actual) throws IOException {
        Files.createDirectories(ACTUAL_REPORT.getParent());
        Files.writeString(ACTUAL_REPORT, actual, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
