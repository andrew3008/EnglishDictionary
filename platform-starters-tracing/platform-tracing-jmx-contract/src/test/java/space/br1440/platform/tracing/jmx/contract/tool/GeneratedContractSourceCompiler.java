package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

final class GeneratedContractSourceCompiler {

    private GeneratedContractSourceCompiler() {
    }

    static Class<?> compileAndLoad(String tempDirPrefix,
                                   String rendererMethodName,
                                   Class<?>[] rendererParameterTypes,
                                   Object[] rendererArguments,
                                   String packageName,
                                   String simpleClassName) throws Exception {
        Path tempDir = Files.createTempDirectory(tempDirPrefix);
        Method renderer = JavaContractRenderer.class.getDeclaredMethod(rendererMethodName, rendererParameterTypes);
        renderer.setAccessible(true);
        String rendered = (String) renderer.invoke(null, rendererArguments);

        Path packageDir = tempDir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Path sourceFile = packageDir.resolve(simpleClassName + ".java");
        Files.writeString(sourceFile, rendered);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available in this runtime");
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(List.of(sourceFile));

            StringWriter diagnostics = new StringWriter();
            boolean success = compiler.getTask(
                    diagnostics, fileManager, null,
                    List.of("-d", tempDir.toString()), null, units
            ).call();

            if (!success) {
                throw new IllegalStateException("Compilation failed: " + diagnostics);
            }
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()},
                ClassLoader.getPlatformClassLoader()
        );
        return Class.forName(packageName + "." + simpleClassName, true, loader);
    }
}
