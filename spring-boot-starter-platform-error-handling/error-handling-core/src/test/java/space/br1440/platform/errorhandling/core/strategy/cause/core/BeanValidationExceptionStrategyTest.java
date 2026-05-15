package space.br1440.platform.errorhandling.core.strategy.cause.core;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeanValidationExceptionStrategyTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private final BeanValidationExceptionStrategy strategy =
            new BeanValidationExceptionStrategy(new StackHintExtractor(true, List.of()));

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    // ---------------------------------------------------------------------------------------
    // supportsCause: только ConstraintViolationException (Jakarta), никаких *-suffix эвристик
    // ---------------------------------------------------------------------------------------

    @Nested
    class SupportsCause {

        @Test
        void supports_jakarta_ConstraintViolationException() {
            assertTrue(strategy.supportsCause(buildConstraintViolationException(new User(null, 5))));
        }

        @Test
        void does_not_support_arbitrary_class_with_ValidationException_suffix() {
            class XmlValidationException extends RuntimeException {
                XmlValidationException(String m) { super(m); }
            }

            assertFalse(strategy.supportsCause(new XmlValidationException("xsd failed")),
                    "Suffix-based matching давал массовый false-positive для инфраструктурных ошибок " +
                            "(XmlValidationException, CertificateValidationException, SamlValidationException и т.п.) — " +
                            "должен быть удалён; кастомные бизнес-исключения регистрируют собственную стратегию через SPI");
        }

        @Test
        void does_not_support_bare_jakarta_ValidationException_parent() {
            assertFalse(strategy.supportsCause(new jakarta.validation.ValidationException("boot error")),
                    "jakarta.validation.ValidationException — родитель для NoProviderFoundException, " +
                            "GroupDefinitionException и пр. инфраструктурных ошибок; не должен давать 400");
        }

        @Test
        void does_not_support_IllegalArgumentException() {
            assertFalse(strategy.supportsCause(new IllegalArgumentException("bad arg")));
        }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP mapping + Retryability
    // ---------------------------------------------------------------------------------------

    @Nested
    class HttpMapping {

        @Test
        void maps_to_400_Bad_Request() {
            RootCauseResolution res =
                    strategy.extractCauseDetails(buildConstraintViolationException(new User(null, 5)));

            assertEquals(HttpStatus.BAD_REQUEST, res.httpStatus());
        }

        @Test
        void declares_NOT_RETRYABLE_explicitly() {
            RootCauseResolution res =
                    strategy.extractCauseDetails(buildConstraintViolationException(new User(null, 5)));

            assertEquals(Retryability.NOT_RETRYABLE, res.retryability(),
                    "Constraint violation детерминирован на одном и том же входе — повтор бесполезен; " +
                            "явная декларация NOT_RETRYABLE нужна SDK-генерации и retry-клиентам");
        }

        @Test
        void message_contains_count_when_violations_present() {
            RootCauseResolution res =
                    strategy.extractCauseDetails(buildConstraintViolationException(new User(null, 5)));

            assertTrue(res.message().contains("Validation failed"));
            assertTrue(res.message().contains("constraint violations"));
        }
    }

    // ---------------------------------------------------------------------------------------
    // ErrorInfoDetail: reason/domain
    // ---------------------------------------------------------------------------------------

    @Nested
    class ReasonAndDomain {

        @Test
        void reason_is_BEAN_VALIDATION() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    buildConstraintViolationException(new User(null, 5))));
            assertEquals("BEAN_VALIDATION", info.getReason());
        }

        @Test
        void domain_is_validation() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    buildConstraintViolationException(new User(null, 5))));
            assertEquals("validation", info.getDomain());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Diagnostic metadata: violationCount, violatedConstraints, rootBeanClass
    // ---------------------------------------------------------------------------------------

    @Nested
    class DiagnosticMetadata {

        @Test
        void reports_violation_count() {
            Map<String, String> diag = diagOf(new User(null, -1));

            int count = Integer.parseInt(diag.get("violationCount"));
            assertTrue(count >= 2, "Обнаружено как минимум 2 нарушения (name + age), получено: " + count);
        }

        @Test
        void violatedConstraints_uses_real_annotation_name_not_proxy_impl() {
            Map<String, String> diag = diagOf(new User(null, -1));

            String violated = diag.get("violatedConstraints");
            assertNotNull(violated);
            assertTrue(violated.contains(":NotNull") || violated.contains(":Min"),
                    "Имя аннотации должно быть получено через annotationType().getSimpleName() — " +
                            "стабильное public имя (NotNull/Min), а не имя proxy-класса (`$Proxy42`, `NotNullImpl`). Было: " + violated);
            assertFalse(violated.contains("$"),
                    "violatedConstraints не должен содержать proxy-суффиксы: " + violated);
            assertFalse(violated.contains("Impl,") || violated.contains("Impl:"),
                    "Наивный replace(\"Impl\", \"\") портит имена типа `ImplicitNumberCheck` — " +
                            "он удалён в пользу annotationType(). Было: " + violated);
        }

        @Test
        void rootBeanClass_uses_simple_name() {
            Map<String, String> diag = diagOf(new User(null, 5));

            assertEquals("User", diag.get("rootBeanClass"),
                    "Используется getSimpleName() — полный FQCN раскрыл бы структуру доменной модели (CWE-209)");
        }

        @Test
        void does_not_include_exceptionClass() {
            Map<String, String> diag = diagOf(new User(null, 5));

            assertFalse(diag.containsKey("exceptionClass"),
                    "После сужения supportsCause до Jakarta/javax ConstraintViolationException " +
                            "значение exceptionClass сводится к двум константам и полностью покрывается " +
                            "парой reason=BEAN_VALIDATION + domain=validation; ключ избыточен");
        }

        @Test
        void stackHint_is_present() {
            Map<String, String> diag = diagOf(new User(null, 5));
            assertTrue(diag.containsKey("stackHint"));
        }

        @Test
        void empty_violations_gives_count_zero_without_violatedConstraints() {
            ConstraintViolationException ex = new ConstraintViolationException("no violations", Set.of());

            Map<String, String> diag = diagFromException(ex);

            assertEquals("0", diag.get("violationCount"));
            assertFalse(diag.containsKey("violatedConstraints"));
            assertFalse(diag.containsKey("rootBeanClass"));
        }

        @Test
        void null_violations_set_is_treated_as_empty() {
            ConstraintViolationException ex = new ConstraintViolationException("legacy", null);

            Map<String, String> diag = diagFromException(ex);

            assertEquals("0", diag.get("violationCount"));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Safety: CWE-117 (log injection), CVE-2021-42574 (Trojan Source), CWE-209 (PII leak),
    // CWE-400 (DoS)
    // ---------------------------------------------------------------------------------------

    @Nested
    class Security {

        @Test
        void sanitizes_CRLF_in_constraint_message() {
            ConstraintViolationException ex =
                    fakeException(fakeViolation("email", "Email", "invalid\r\nINFO fake log line"));

            Map<String, String> diag = diagFromException(ex);

            String violated = diag.get("violatedConstraints");
            assertNotNull(violated);
            assertFalse(violated.contains("\r"), "CR вырезан: " + violated);
            assertFalse(violated.contains("\n"), "LF вырезан: " + violated);
        }

        @Test
        void sanitizes_bidi_override_in_constraint_message() {
            ConstraintViolationException ex =
                    fakeException(fakeViolation("name", "Size", "too short \u202Eevil"));

            Map<String, String> diag = diagFromException(ex);

            String violated = diag.get("violatedConstraints");
            assertFalse(violated.contains("\u202E"),
                    "Bidi override-символ (CVE-2021-42574) должен быть удалён: " + violated);
        }

        @Test
        void truncates_individual_message_at_max_length() {
            String payload = "a".repeat(BeanValidationExceptionStrategy.MAX_VIOLATION_MESSAGE_LENGTH + 50);
            ConstraintViolationException ex = fakeException(fakeViolation("name", "Size", payload));

            Map<String, String> diag = diagFromException(ex);

            String violated = diag.get("violatedConstraints");
            assertTrue(violated.contains("[TRUNCATED]"),
                    "Длинное сообщение должно быть обрезано с маркером [TRUNCATED]: " + violated);
        }

        @Test
        void does_not_leak_invalidValue_into_metadata() {
            // Нарушение @Email: значение поля — сам «пароль» / PII, который по API
            // доступен через getInvalidValue(). Стратегия не должна его раскрывать.
            String secret = "secret-password-placeholder";
            Map<String, String> diag = diagOf(new Contact(secret, "ok"));

            for (String value : diag.values()) {
                assertFalse(value != null && value.contains(secret),
                        "Отклонённое значение (getInvalidValue()) — потенциально PII/пароль — " +
                                "не должно попадать в metadata: " + value);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // DoS hardening: cap количества и общей длины violations
    // ---------------------------------------------------------------------------------------

    @Nested
    class DoSHardening {

        @Test
        void caps_violations_in_metadata_and_marks_truncated() {
            Set<ConstraintViolation<?>> many = new LinkedHashSet<>();
            for (int i = 0; i < BeanValidationExceptionStrategy.MAX_VIOLATIONS_IN_METADATA + 10; i++) {
                many.add(fakeViolation("field" + i, "NotNull", "must not be null"));
            }
            ConstraintViolationException ex = new ConstraintViolationException("dos", many);

            Map<String, String> diag = diagFromException(ex);

            assertEquals(String.valueOf(BeanValidationExceptionStrategy.MAX_VIOLATIONS_IN_METADATA + 10),
                    diag.get("violationCount"),
                    "violationCount должен отражать полное число, а не усечённое — для честных SLO");
            assertEquals("true", diag.get("violationsTruncated"),
                    "Факт усечения должен быть зафиксирован для SRE");

            String violated = diag.get("violatedConstraints");
            long commas = violated.chars().filter(c -> c == ',').count();
            assertTrue(commas <= BeanValidationExceptionStrategy.MAX_VIOLATIONS_IN_METADATA,
                    "Число записей не должно превышать cap: commas=" + commas);
        }

        @Test
        void caps_total_length_of_violated_constraints() {
            String longMsg = "m".repeat(BeanValidationExceptionStrategy.MAX_VIOLATION_MESSAGE_LENGTH);

            Set<ConstraintViolation<?>> many = new LinkedHashSet<>();
            for (int i = 0; i < 50; i++) {
                many.add(fakeViolation("field" + i, "Size", longMsg));
            }
            ConstraintViolationException ex = new ConstraintViolationException("dos", many);

            Map<String, String> diag = diagFromException(ex);

            String violated = diag.get("violatedConstraints");
            assertTrue(violated.length() <= BeanValidationExceptionStrategy.MAX_VIOLATED_CONSTRAINTS_LENGTH,
                    "violatedConstraints длина > cap: " + violated.length());
            assertEquals("true", diag.get("violationsTruncated"));
        }

        @Test
        void caps_total_scan_at_MAX_VIOLATIONS_TO_SCAN() {
            int total = BeanValidationExceptionStrategy.MAX_VIOLATIONS_TO_SCAN + 50;

            Set<ConstraintViolation<?>> tooMany = new LinkedHashSet<>();
            for (int i = 0; i < total; i++) {
                tooMany.add(fakeViolation("field" + i, "NotNull", "must not be null"));
            }
            ConstraintViolationException ex = new ConstraintViolationException("scan-cap", tooMany);

            Map<String, String> diag = diagFromException(ex);

            assertEquals(String.valueOf(total), diag.get("violationCount"),
                    "violationCount возвращает Set.size() (O(1)) и не зависит от обхода — для честных SLO");
            assertEquals("true", diag.get("violationsTruncated"),
                    "Обход остановлен на MAX_VIOLATIONS_TO_SCAN, факт усечения должен быть зафиксирован для SRE");
        }

        @Test
        void truncates_long_property_path() {
            String longPath = "user." + "attribute.".repeat(20) + "value";
            assertTrue(longPath.length() > BeanValidationExceptionStrategy.MAX_PROPERTY_PATH_LENGTH,
                    "Тестовая фиктура должна превышать MAX_PROPERTY_PATH_LENGTH");

            ConstraintViolationException ex =
                    fakeException(fakeViolation(longPath, "NotNull", "must not be null"));

            Map<String, String> diag = diagFromException(ex);

            String violated = diag.get("violatedConstraints");
            assertNotNull(violated);
            assertTrue(violated.contains("[TRUNCATED]"),
                    "Длинный property-path должен быть обрезан с маркером [TRUNCATED]: " + violated);
            assertFalse(violated.contains(longPath),
                    "Полный непрерывный path не должен попадать в metadata: " + violated);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Multi-bean violations: rootBeanClass = "MULTIPLE"
    // ---------------------------------------------------------------------------------------

    @Nested
    class MultipleRootBeans {

        @Test
        void reports_MULTIPLE_when_violations_span_different_root_beans() {
            Set<ConstraintViolation<?>> mixed = new HashSet<>();
            mixed.addAll(validator.validate(new User(null, 5)));
            mixed.addAll(validator.validate(new Order(null)));

            ConstraintViolationException ex = new ConstraintViolationException("cross-bean", mixed);
            Map<String, String> diag = diagFromException(ex);

            assertEquals("MULTIPLE", diag.get("rootBeanClass"),
                    "При нарушениях в разных root-bean стратегия не должна скрывать факт за именем первого");
        }
    }

    // =======================================================================================
    // helpers & fixtures
    // =======================================================================================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ConstraintViolationException buildConstraintViolationException(Object target) {
        Set<ConstraintViolation<Object>> violations = (Set) validator.validate(target);
        if (violations.isEmpty()) {
            throw new AssertionError("Test fixture expected to produce violations but yielded none for: " + target);
        }
        return new ConstraintViolationException("validation failed", (Set) violations);
    }

    private Map<String, String> diagOf(Object target) {
        return diagFromException(buildConstraintViolationException(target));
    }

    private Map<String, String> diagFromException(ConstraintViolationException ex) {
        return extractInfo(strategy.extractCauseDetails(ex)).getDiagnosticMetadata();
    }

    private static ErrorInfoDetail extractInfo(RootCauseResolution resolution) {
        ErrorDetail first = resolution.details().get(0);
        if (first instanceof ErrorInfoDetail info) {
            return info;
        }
        throw new AssertionError("Expected ErrorInfoDetail, got: " + first.getClass());
    }

    // --- FakeConstraintViolation для тестов CR/LF/bidi/DoS ---------------------------------

    private static ConstraintViolationException fakeException(ConstraintViolation<?> single) {
        return new ConstraintViolationException("fake", Set.of(single));
    }

    private static ConstraintViolation<?> fakeViolation(String propertyPath,
                                                        String constraintSimpleName,
                                                        String message) {
        return new FakeConstraintViolation(propertyPath, constraintSimpleName, message);
    }

    // --- fixture beans ----------------------------------------------------------------------

    static class User {
        @NotNull
        final String name;

        @Min(0)
        final int age;

        User(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }

    static class Order {
        @NotBlank
        final String id;

        Order(String id) {
            this.id = id;
        }
    }

    static class Contact {
        @Email
        final String email;

        @Size(min = 1, max = 5)
        final String code;

        Contact(String email, String code) {
            this.email = email;
            this.code = code;
        }
    }
}
