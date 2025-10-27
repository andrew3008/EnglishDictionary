package space.br1440.platform.leaderelection.validation;

import space.br1440.platform.leaderelection.annotation.leadership.OnLeaderChange;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Set;

@SupportedAnnotationTypes("space.br1440.platform.leaderelection.annotation.leadership.OnLeaderChange")
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class LeaderChangeValidationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(OnLeaderChange.class)) {
            if (element.getKind() == ElementKind.METHOD) {
                validateMethod((ExecutableElement) element);
            } else {
                String message = "@OnLeaderChange can only be applied to methods";
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        message,
                        element
                );
            }
        }
        return true;
    }

    private void validateMethod(ExecutableElement method) {
        String methodName = method.getSimpleName().toString();
        String className = method.getEnclosingElement().getSimpleName().toString();
        String fullMethodName = className + "." + methodName;

        if (method.getModifiers().contains(Modifier.PRIVATE)) {
            String message = """
                    Method [%s] is private and not suitable for @OnLeaderChange. \
                    Make the method public or package-private.
                    """.formatted(fullMethodName);

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    message,
                    method
            );
        }

        if (method.getModifiers().contains(Modifier.STATIC)) {
            String message = """
                    Method [%s] is static and may not work as expected with @OnLeaderChange. \
                    Consider making it non-static.
                    """.formatted(fullMethodName);

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    message,
                    method
            );
        }

        OnLeaderChange annotation = method.getAnnotation(OnLeaderChange.class);
        if (annotation != null && annotation.async()) {
            TypeMirror returnType = method.getReturnType();
            if (!isVoidOrVoidCompatible(returnType)) {
                String message = """
                        Async method [%s] should return void or CompletableFuture for @OnLeaderChange. \
                        Current return type: %s
                        """.formatted(fullMethodName, returnType);

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        message,
                        method
                );
            }
        }

        if (annotation != null && annotation.async() && !method.getParameters().isEmpty()) {
            String message = """
                       Async method [%s] has parameters. \
                       Consider if parameters are accessible in async context.
                    """.formatted(fullMethodName);

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.WARNING,
                    message,
                    method
            );
        }
    }

    private boolean isVoidOrVoidCompatible(TypeMirror returnType) {
        if (returnType.getKind() == TypeKind.VOID) {
            return true;
        }

        if (returnType.toString().equals("java.lang.Void")) {
            return true;
        }

        if (returnType.toString().startsWith("java.util.concurrent.CompletableFuture")) {
            return true;
        }

        return false;
    }

}