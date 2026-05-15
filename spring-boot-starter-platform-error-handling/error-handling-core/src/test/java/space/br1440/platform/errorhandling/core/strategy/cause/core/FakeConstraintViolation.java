package space.br1440.platform.errorhandling.core.strategy.cause.core;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;

import java.lang.annotation.Annotation;

/**
 * Тестовый fake {@link ConstraintViolation} для проверки санитизации, truncation,
 * DoS cap'ов и извлечения имени аннотации без подключения реального Hibernate
 * Validator engine к сфабрикованным сценариям (недопустимое сообщение, массовые
 * violations и т.п.).
 *
 * <p>Возвращает только те поля, которые используются стратегией; остальные методы
 * контракта {@link ConstraintViolation} бросают {@link UnsupportedOperationException}
 * по принципу fail-fast — если стратегия неожиданно начнёт их вызывать, тест упадёт
 * с явным stack-trace.
 */
final class FakeConstraintViolation implements ConstraintViolation<Object> {

    private final String propertyPath;
    private final String constraintSimpleName;
    private final String message;

    FakeConstraintViolation(String propertyPath, String constraintSimpleName, String message) {
        this.propertyPath = propertyPath;
        this.constraintSimpleName = constraintSimpleName;
        this.message = message;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public Path getPropertyPath() {
        return new SingleNodePath(propertyPath);
    }

    @Override
    public Class<Object> getRootBeanClass() {
        return Object.class;
    }

    @Override
    public ConstraintDescriptor<?> getConstraintDescriptor() {
        return new FakeConstraintDescriptor(constraintSimpleName);
    }

    // --- не используемые стратегией методы (fail-fast) -------------------------------------

    @Override
    public String getMessageTemplate() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getRootBean() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getLeafBean() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object[] getExecutableParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getExecutableReturnValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getInvalidValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U> U unwrap(Class<U> type) {
        throw new UnsupportedOperationException();
    }

    // --- inner fakes -----------------------------------------------------------------------

    private record SingleNodePath(String text) implements Path {

        @Override
        public java.util.Iterator<Node> iterator() {
            throw new UnsupportedOperationException("not used by strategy");
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * Динамически создаёт {@link Annotation} с указанным {@code annotationType().getSimpleName()} —
     * точно так, как Hibernate Validator возвращает в runtime.
     */
    private static final class FakeConstraintDescriptor implements ConstraintDescriptor<Annotation> {

        private final Annotation annotation;

        FakeConstraintDescriptor(String simpleName) {
            this.annotation = SyntheticAnnotations.of(simpleName);
        }

        @Override
        public Annotation getAnnotation() {
            return annotation;
        }

        @Override
        public String getMessageTemplate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Set<Class<?>> getGroups() {
            return java.util.Set.of();
        }

        @Override
        public java.util.Set<Class<? extends jakarta.validation.Payload>> getPayload() {
            return java.util.Set.of();
        }

        @Override
        public jakarta.validation.ConstraintTarget getValidationAppliesTo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> getConstraintValidatorClasses() {
            return java.util.List.of();
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return java.util.Map.of();
        }

        @Override
        public java.util.Set<ConstraintDescriptor<?>> getComposingConstraints() {
            return java.util.Set.of();
        }

        @Override
        public boolean isReportAsSingleViolation() {
            return false;
        }

        @Override
        public jakarta.validation.metadata.ValidateUnwrappedValue getValueUnwrapping() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            throw new UnsupportedOperationException();
        }
    }
}
