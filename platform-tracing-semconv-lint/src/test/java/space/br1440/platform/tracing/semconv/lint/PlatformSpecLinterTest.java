package space.br1440.platform.tracing.semconv.lint;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSpecLinterTest {

    @Test
    void чистый_span_проходит_все_дефолтные_правила() {
        Map<String, String> resource = Map.of(
                "service.name", "demo",
                "platform.c_group", "billing",
                "platform.id", "demo-001",
                "deployment.environment.name", "prod"
        );
        Map<String, String> attrs = new HashMap<>();
        attrs.put("platform.trace.type", "http_server");
        attrs.put("platform.trace.result", "success");
        attrs.put("http.request.method", "GET");
        attrs.put("http.response.status_code", "200");

        SpanRecord span = new SpanRecord("GET /api", "SERVER", "OK", attrs, resource);
        LintReport report = PlatformSpec.defaultLinter().lint(List.of(span));

        assertThat(report.violations()).isEmpty();
        assertThat(report.isPassing()).isTrue();
    }

    @Test
    void отсутствие_обязательных_платформенных_атрибутов_дает_ERROR() {
        SpanRecord span = new SpanRecord("op", "INTERNAL", "OK", Map.of(), Map.of());
        LintReport report = PlatformSpec.defaultLinter().lint(List.of(span));

        assertThat(report.errorCount()).isPositive();
        assertThat(report.violations())
                .extracting(LintViolation::ruleId)
                .contains("PLATFORM_SERVICE_NAME_REQUIRED",
                        "PLATFORM_C_GROUP_REQUIRED",
                        "PLATFORM_ID_REQUIRED",
                        "PLATFORM_TYPE_REQUIRED",
                        "PLATFORM_RESULT_REQUIRED");
    }

    @Test
    void нестандартное_значение_platform_type_фиксируется_правилом_allowedValues() {
        Map<String, String> resource = Map.of(
                "service.name", "demo",
                "platform.c_group", "billing",
                "platform.id", "demo-001"
        );
        Map<String, String> attrs = Map.of(
                "platform.trace.type", "weird-type",
                "platform.trace.result", "success"
        );

        SpanRecord span = new SpanRecord("op", "INTERNAL", "OK", attrs, resource);
        LintReport report = PlatformSpec.defaultLinter().lint(List.of(span));

        assertThat(report.violations())
                .extracting(LintViolation::ruleId)
                .contains("PLATFORM_TYPE_REQUIRED");
        assertThat(report.violations())
                .filteredOn(v -> v.ruleId().equals("PLATFORM_TYPE_REQUIRED"))
                .first()
                .extracting(LintViolation::message)
                .asString()
                .contains("weird-type");
    }

    @Test
    void нестандартный_environment_дает_только_WARNING_не_ERROR() {
        Map<String, String> resource = Map.of(
                "service.name", "demo",
                "platform.c_group", "billing",
                "platform.id", "demo-001",
                "deployment.environment.name", "exotic"
        );
        Map<String, String> attrs = Map.of(
                "platform.trace.type", "internal",
                "platform.trace.result", "success"
        );

        SpanRecord span = new SpanRecord("op", "INTERNAL", "OK", attrs, resource);
        LintReport report = PlatformSpec.defaultLinter().lint(List.of(span));

        assertThat(report.errorCount()).isZero();
        assertThat(report.warningCount()).isPositive();
        assertThat(report.violations())
                .extracting(LintViolation::severity)
                .contains(LintSeverity.WARNING);
    }

    @Test
    void HTTP_SERVER_без_method_и_status_фиксируется_отдельными_правилами() {
        Map<String, String> resource = Map.of(
                "service.name", "demo",
                "platform.c_group", "billing",
                "platform.id", "demo-001"
        );
        Map<String, String> attrs = Map.of(
                "platform.trace.type", "http_server",
                "platform.trace.result", "success"
        );

        SpanRecord span = new SpanRecord("GET /api", "SERVER", "OK", attrs, resource);
        LintReport report = PlatformSpec.defaultLinter().lint(List.of(span));

        assertThat(report.violations())
                .extracting(LintViolation::ruleId)
                .contains("HTTP_SERVER_METHOD_REQUIRED", "HTTP_SERVER_STATUS_REQUIRED");
    }

    @Test
    void HTTP_правила_не_применяются_к_INTERNAL_span_у() {
        Map<String, String> resource = Map.of(
                "service.name", "demo",
                "platform.c_group", "billing",
                "platform.id", "demo-001"
        );
        Map<String, String> attrs = Map.of(
                "platform.trace.type", "internal",
                "platform.trace.result", "success"
        );

        SpanRecord span = new SpanRecord("op", "INTERNAL", "OK", attrs, resource);
        LintReport report = PlatformSpec.defaultLinter().lint(List.of(span));

        assertThat(report.violations())
                .extracting(LintViolation::ruleId)
                .doesNotContain("HTTP_SERVER_METHOD_REQUIRED", "HTTP_SERVER_STATUS_REQUIRED");
    }
}
