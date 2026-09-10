package space.br1440.platform.tracing.gradle;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;

final class JibExtensionAccess {

    private final Object container;
    private final Object extraDirectories;
    private final Object pluginExtensions;

    private JibExtensionAccess(Object container, Object extraDirectories, Object pluginExtensions) {
        this.container = container;
        this.extraDirectories = extraDirectories;
        this.pluginExtensions = pluginExtensions;
    }

    static JibExtensionAccess of(Project project) {
        Object jib = project.getExtensions().getByName("jib");
        return new JibExtensionAccess(
                invoke(jib, "getContainer"),
                invoke(jib, "getExtraDirectories"),
                invoke(jib, "getPluginExtensions")
        );
    }

    @SuppressWarnings("unchecked")
    List<String> jvmFlags() {
        return new ArrayList<>((List<String>) invoke(container, "getJvmFlags"));
    }

    void setJvmFlags(List<String> flags) {
        invoke(container, "setJvmFlags", new Class<?>[] {List.class}, flags);
    }

    @SuppressWarnings("unchecked")
    List<String> entrypoint() {
        List<String> entrypoint = (List<String>) invoke(container, "getEntrypoint");
        return entrypoint == null ? List.of() : List.copyOf(entrypoint);
    }

    @SuppressWarnings("unchecked")
    Map<String, String> environment() {
        return Map.copyOf((Map<String, String>) invoke(container, "getEnvironment"));
    }

    List<String> extraDirectoryDestinations() {
        List<String> destinations = new ArrayList<>();
        for (Object extraDirectory : extraDirectoryPaths()) {
            destinations.add((String) invoke(extraDirectory, "getInto"));
        }

        return List.copyOf(destinations);
    }

    @SuppressWarnings("unchecked")
    List<String> pluginExtensionImplementations() {
        List<Object> extensions = (List<Object>) invoke(pluginExtensions, "get");
        List<String> implementations = new ArrayList<>();
        for (Object extension : extensions) {
            implementations.add((String) invoke(extension, "getImplementation"));
        }
        return List.copyOf(implementations);
    }

    boolean onlyImplicitDefaultExtraDirectory(Project project) {
        List<Object> paths = extraDirectoryPaths();
        if (paths.size() != 1) {
            return false;
        }

        Object only = paths.get(0);
        Path from = (Path) invoke(only, "getFrom");
        String into = (String) invoke(only, "getInto");
        return "/".equals(into) && implicitJibExtraDirectory(project).equals(from);
    }

    void addExtraDirectory(File from, String into) {
        Action<Object> configurePath = path -> {
            invoke(path, "setFrom", new Class<?>[] {Object.class}, from);
            invoke(path, "setInto", new Class<?>[] {String.class}, into);
        };

        Action<Object> configureSpec = spec -> invoke(spec, "path", new Class<?>[] {Action.class}, configurePath);
        invoke(extraDirectories, "paths", new Class<?>[] {Action.class}, configureSpec);
    }

    static Path implicitJibExtraDirectory(Project project) {
        return project.getProjectDir().toPath().resolve("src").resolve("main").resolve("jib");
    }

    @SuppressWarnings("unchecked")
    private List<Object> extraDirectoryPaths() {
        return (List<Object>) invoke(extraDirectories, "getPaths");
    }

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            String message = "Jib extension method %s failed: %s".formatted(methodName, cause.getMessage());
            throw new GradleException(message, cause);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            String message = "Unsupported Jib API: %s.%s.".formatted(target.getClass().getName(), methodName);
            throw new GradleException(message, failure);
        }
    }
}
