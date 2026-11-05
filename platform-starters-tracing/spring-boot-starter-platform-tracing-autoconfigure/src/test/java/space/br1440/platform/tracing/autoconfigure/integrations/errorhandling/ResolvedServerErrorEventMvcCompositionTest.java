package space.br1440.platform.tracing.autoconfigure.integrations.errorhandling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import space.br1440.platform.errorhandling.autoconfigure.ErrorHandlingCoreAutoConfiguration;
import space.br1440.platform.errorhandling.autoconfigure.ErrorHandlingMvcAutoConfiguration;
import space.br1440.platform.errorhandling.core.advice.GlobalExceptionHandler;
import space.br1440.platform.tracing.autoconfigure.exception.ExceptionRecordingAutoConfiguration;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class ResolvedServerErrorEventMvcCompositionTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    ErrorHandlingCoreAutoConfiguration.class,
                    ErrorHandlingMvcAutoConfiguration.class,
                    ExceptionRecordingAutoConfiguration.class,
                    ResolvedServerErrorEventAutoConfiguration.class
            ))
            .withPropertyValues("spring.application.name=composition")
            .withUserConfiguration(FailingController.class);

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void resolvedServerErrorAddsSingleSanitizedEventWithoutOutcomeWrites() {
        contextRunner.run(context -> {
            // given
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext()).build();

            // when
            ServerCall call = perform(mockMvc, "/failures/unknown");

            // then
            assertThat(call.status()).isEqualTo(500);
            assertThat(call.span().getEvents()).singleElement().satisfies(event -> {
                assertThat(event.getName()).isEqualTo("exception");
                assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                        .isEqualTo(IllegalStateException.class.getName());
                assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("inventory unavailable");
                assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
            });
            assertThat(call.span().getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(call.span().getStatus().getDescription()).isEmpty();
            assertThat(call.span().getAttributes().get(SemconvKeys.ERROR_TYPE)).isNull();
            assertThat(call.span().getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
        });
    }

    @Test
    void resolvedServiceUnavailableAddsSingleEvent() {
        contextRunner.run(context -> {
            // given
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext()).build();

            // when
            ServerCall call = perform(mockMvc, "/failures/unavailable");

            // then
            assertThat(call.status()).isEqualTo(503);
            assertThat(call.span().getEvents()).hasSize(1);
            assertThat(call.span().getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
        });
    }

    @Test
    void resolvedClientErrorAddsNoEvent() {
        contextRunner.run(context -> {
            // given
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext()).build();

            // when
            ServerCall call = perform(mockMvc, "/failures/bad-request");

            // then
            assertThat(call.status()).isEqualTo(400);
            assertThat(call.span().getEvents()).isEmpty();
            assertThat(call.span().getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
            assertThat(call.span().getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
        });
    }

    @Test
    void successfulRequestAddsNoEvent() {
        contextRunner.run(context -> {
            // given
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext()).build();

            // when
            ServerCall call = perform(mockMvc, "/failures/ok");

            // then
            assertThat(call.status()).isEqualTo(200);
            assertThat(call.span().getEvents()).isEmpty();
            assertThat(call.span().getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
        });
    }

    @Test
    void tracingDisabledKeepsErrorResponseWithoutEvent() {
        contextRunner.withPropertyValues("platform.tracing.enabled=false").run(context -> {
            // given
            MockMvc mockMvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context.getSourceApplicationContext()).build();

            // when
            ServerCall call = perform(mockMvc, "/failures/unknown");

            // then
            assertThat(call.status()).isEqualTo(500);
            assertThat(call.span().getEvents()).isEmpty();
        });
    }

    private ServerCall perform(MockMvc mockMvc, String path) throws Exception {
        Span span = tracerProvider.get("test").spanBuilder("GET " + path).startSpan();
        MvcResult result;
        try (Scope ignored = span.makeCurrent()) {
            result = mockMvc.perform(get(path)).andReturn();
        } finally {
            span.end();
        }

        return new ServerCall(result.getResponse().getStatus(), exporter.getFinishedSpanItems().getLast());
    }

    private record ServerCall(int status, SpanData span) {
    }

    @RestController
    static class FailingController {

        @GetMapping("/failures/unknown")
        String unknown() {
            throw new IllegalStateException("inventory unavailable");
        }

        @GetMapping("/failures/unavailable")
        String unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "maintenance");
        }

        @GetMapping("/failures/bad-request")
        String badRequest() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid order");
        }

        @GetMapping("/failures/ok")
        String ok() {
            return "ok";
        }
    }
}
