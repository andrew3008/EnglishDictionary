package space.br1440.platform.leaderelection.validation;

import space.br1440.platform.leaderelection.annotation.leadership.LeaderAware;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Set;

@SupportedAnnotationTypes({
        "space.br1440.platform.leaderelection.annotation.leadership.LeaderAware"
})
@SupportedSourceVersion(SourceVersion.RELEASE_11)
public class LeaderAwareValidationProcessor extends AbstractProcessor {

    private static final String BOOLEAN_TYPE = "boolean";
    private static final String LEADER_INFO_TYPE = "space.br1440.platform.leaderelection.leaderelection.model.LeaderInfo";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(LeaderAware.class)) {
            if (element.getKind() == ElementKind.FIELD) {
                validateField(element);
            } else if (element.getKind() == ElementKind.METHOD) {
                validateMethod(element);
            }
        }

        return true;
    }

    private void validateField(Element fieldElement) {
        VariableElement variableElement = (VariableElement) fieldElement;
        TypeMirror fieldType = variableElement.asType();
        String fieldTypeName = fieldType.toString();

        if (BOOLEAN_TYPE.equals(fieldTypeName) || 
            BOOLEAN_TYPE.equalsIgnoreCase(fieldType.getKind().toString())) {
            return;
        }

        if (LEADER_INFO_TYPE.equals(fieldTypeName)) {
            return;
        }

        String message = String.format(
                "@LeaderAware не может применяться к полю типа '%s'. " +
                "Поддерживаются только: boolean, LeaderInfo",
                fieldTypeName
        );

        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                message,
                fieldElement
        );
    }

    private void validateMethod(Element methodElement) {
        ExecutableElement executableElement = (ExecutableElement) methodElement;
        int paramCount = executableElement.getParameters().size();

        if (paramCount == 0) {
            return;
        }

        if (paramCount == 1) {
            VariableElement paramElement = executableElement.getParameters().getFirst();
            TypeMirror paramType = paramElement.asType();
            String paramTypeName = paramType.toString();

            if (BOOLEAN_TYPE.equals(paramTypeName) || 
                BOOLEAN_TYPE.equalsIgnoreCase(paramType.getKind().toString())) {
                return;
            }

            String message = String.format(
                    "@LeaderAware не может применяться к методу с параметром типа '%s'. " +
                    "Поддерживаются только: void() или void(boolean)",
                    paramTypeName
            );

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    message,
                    methodElement
            );
            return;
        }

        String message = String.format(
                "@LeaderAware не может применяться к методу с %d параметрами. " +
                "Поддерживаются только: 0 параметров или 1 boolean параметр",
                paramCount
        );

        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                message,
                methodElement
        );
    }

}
