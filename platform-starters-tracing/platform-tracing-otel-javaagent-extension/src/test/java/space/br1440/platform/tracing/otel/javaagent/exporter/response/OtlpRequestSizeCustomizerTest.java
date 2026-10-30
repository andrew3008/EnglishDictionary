package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.OtlpRequestSizeCustomizer;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.common.ComponentLoader;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcSenderConfig;
import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import io.opentelemetry.sdk.common.export.GrpcStatusCode;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.common.export.MessageWriter;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class OtlpRequestSizeCustomizerTest {

    @Test
    void otherExportersAreNeitherReplacedNorClosed() {
        // given
        SpanExporter original = mock(SpanExporter.class);

        // when
        SpanExporter replacement = OtlpRequestSizeCustomizer.customize(original, config(false));

        // then
        assertThat(replacement).isSameAs(original);
        verifyNoInteractions(original);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void replacementPreservesConfigurationAndClosesOnlyOriginalSender(Protocol protocol) throws Exception {
        // given
        SenderCapture senders = new SenderCapture();
        SSLContext sslContext = SSLContext.getDefault();
        X509TrustManager trustManager = mock(X509TrustManager.class);
        AtomicReference<MeterProvider> meters = new AtomicReference<>(MeterProvider.noop());
        Supplier<MeterProvider> meterSupplier = meters::get;
        AtomicReference<String> header = new AtomicReference<>("before");
        ProxyOptions proxy = ProxyOptions.create(new InetSocketAddress("localhost", 8181));
        RetryPolicy retryPolicy = RetryPolicy.builder().setMaxAttempts(2).build();
        SpanExporter configured = protocol == Protocol.GRPC
                ? OtlpGrpcSpanExporter.builder()
                        .setComponentLoader(senders)
                        .setEndpoint("https://collector.example:14317")
                        .setTimeout(Duration.ofSeconds(7))
                        .setConnectTimeout(Duration.ofSeconds(3))
                        .setCompression("gzip")
                        .setSslContext(sslContext, trustManager)
                        .setEnabledProtocols(List.of("TLSv1.3"))
                        .addHeader("constant", "kept")
                        .setHeaders(() -> Map.of("dynamic", header.get()))
                        .setRetryPolicy(retryPolicy)
                        .setMeterProvider(meterSupplier)
                        .setMemoryMode(MemoryMode.IMMUTABLE_DATA)
                        .build()
                : OtlpHttpSpanExporter.builder()
                        .setComponentLoader(senders)
                        .setEndpoint("https://collector.example:14318/custom/traces")
                        .setTimeout(Duration.ofSeconds(7))
                        .setConnectTimeout(Duration.ofSeconds(3))
                        .setCompression("gzip")
                        .setSslContext(sslContext, trustManager)
                        .setEnabledProtocols(List.of("TLSv1.3"))
                        .addHeader("constant", "kept")
                        .setHeaders(() -> Map.of("dynamic", header.get()))
                        .setRetryPolicy(retryPolicy)
                        .setMeterProvider(meterSupplier)
                        .setMemoryMode(MemoryMode.IMMUTABLE_DATA)
                        .setProxy(proxy)
                        .build();
        SpanExporter original = spy(configured);

        // when
        try (SpanExporter replacement = OtlpRequestSizeCustomizer.customize(original, config(false))) {
            header.set("after");
            MeterProvider updatedMeters = mock(MeterProvider.class);
            meters.set(updatedMeters);

            // then
            assertThat(replacement).isNotSameAs(original);
            SpanExporter copiedExporter = (SpanExporter) field(replacement, "delegate");
            assertThat(copiedExporter).isExactlyInstanceOf(original.getClass());
            assertThat(senders.states).hasSize(2);
            assertThat(senders.states.getFirst().shutdownCalls).isEqualTo(1);
            verify(original).shutdown();
            assertThat(senders.states.getLast().shutdownCalls).isZero();
            assertThat(field(field(copiedExporter, "builder"), "meterProviderSupplier")).isSameAs(meterSupplier);
            assertThat(meterSupplier.get()).isSameAs(updatedMeters);
            assertThat(field(field(copiedExporter, "marshaler"), "memoryMode"))
                    .isEqualTo(MemoryMode.IMMUTABLE_DATA);
            if (protocol == Protocol.GRPC) {
                GrpcSenderConfig copied = senders.grpcConfigs.getLast();
                assertThat(copied.getEndpoint()).hasToString("https://collector.example:14317");
                assertThat(copied.getTimeout()).isEqualTo(Duration.ofSeconds(7));
                assertThat(copied.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(copied.getCompressor()).isSameAs(senders.grpcConfigs.getFirst().getCompressor());
                assertThat(copied.getSslContext()).isSameAs(sslContext);
                assertThat(copied.getTrustManager()).isSameAs(trustManager);
                assertThat(copied.getEnabledProtocols()).containsExactly("TLSv1.3");
                assertThat(copied.getHeadersSupplier().get()).containsEntry("constant", List.of("kept"))
                        .containsEntry("dynamic", List.of("after"));
                assertThat(copied.getRetryPolicy()).isEqualTo(retryPolicy);
                assertThat(field(field(copiedExporter, "delegate"), "maxRequestMessageSize"))
                        .isEqualTo(15 * 1024L * 1024L);
                assertThat(field(field(copiedExporter, "delegate"), "grpcSender").getClass().getSimpleName())
                        .isEqualTo("ObservingGrpcSender");
            } else {
                HttpSenderConfig copied = senders.httpConfigs.getLast();
                assertThat(copied.getEndpoint()).hasToString("https://collector.example:14318/custom/traces");
                assertThat(copied.getTimeout()).isEqualTo(Duration.ofSeconds(7));
                assertThat(copied.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(copied.getCompressor()).isSameAs(senders.httpConfigs.getFirst().getCompressor());
                assertThat(copied.getSslContext()).isSameAs(sslContext);
                assertThat(copied.getTrustManager()).isSameAs(trustManager);
                assertThat(copied.getEnabledProtocols()).containsExactly("TLSv1.3");
                assertThat(copied.getHeadersSupplier().get()).containsEntry("constant", List.of("kept"))
                        .containsEntry("dynamic", List.of("after"));
                assertThat(copied.getRetryPolicy()).isEqualTo(retryPolicy);
                assertThat(copied.getProxyOptions()).isSameAs(proxy);
                assertThat(field(field(copiedExporter, "delegate"), "maxRequestBodySize"))
                        .isEqualTo(15 * 1024L * 1024L);
                assertThat(field(field(copiedExporter, "delegate"), "httpSender").getClass().getSimpleName())
                        .isEqualTo("ObservingHttpSender");
            }
            meters.set(MeterProvider.noop());
            assertThat(replacement.export(List.of(span("valid"))).isSuccess()).isTrue();
            assertThat(senders.states.getLast().sendCalls).isEqualTo(1);
            assertThat(senders.states.getFirst().sendCalls).isZero();
        }
        assertThat(senders.states.getFirst().shutdownCalls).isEqualTo(1);
        assertThat(senders.states.getLast().shutdownCalls).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void disabledRetryStaysDisabledInReplacement(Protocol protocol) {
        // given
        SenderCapture senders = new SenderCapture();
        SpanExporter original = exporter(protocol, senders, true);

        // when
        try (SpanExporter replacement = OtlpRequestSizeCustomizer.customize(original, config(true))) {
            CompletableResultCode result = replacement.export(List.of(span("valid")));

            // then
            assertThat(result.isSuccess()).isTrue();
            if (protocol == Protocol.GRPC) {
                assertThat(senders.grpcConfigs).hasSize(2).allSatisfy(sender ->
                        assertThat(sender.getRetryPolicy()).isNull());
            } else {
                assertThat(senders.httpConfigs).hasSize(2).allSatisfy(sender ->
                        assertThat(sender.getRetryPolicy()).isNull());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void exactLimitPassesAndOneExtraByteIsDroppedWithoutTransportFailure(Protocol protocol) {
        // given
        SenderCapture senders = new SenderCapture();
        SpanExporter original = exporter(protocol, senders, false);
        int probeLength = Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES) - 256;
        assertThat(original.export(List.of(spanWithPayload("a".repeat(probeLength)))).isSuccess()).isTrue();
        int overhead = senders.states.getFirst().contentLength - probeLength;
        String exactPayload = "a".repeat(Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES) - overhead);
        SpanExporter replacement = OtlpRequestSizeCustomizer.customize(original, config(false));
        try (SafeSpanExporter safe = new SafeSpanExporter(replacement,
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                ScrubbingRuntimeTestFixtures.disabled());
             AgentLogCapture capture = AgentLogCapture.installed()) {
            // when
            CompletableResultCode accepted = safe.export(List.of(spanWithPayload(exactPayload)));
            CompletableResultCode rejected = safe.export(List.of(spanWithPayload(exactPayload + "b")));

            // then
            assertThat(accepted.isSuccess()).isTrue();
            assertThat(rejected.isDone()).isTrue();
            assertThat(rejected.isSuccess()).as("local drop failure: %s", rejected.getFailureThrowable()).isTrue();
            assertThat(senders.states.getLast().sendCalls).isEqualTo(1);
            assertThat(senders.states.getLast().contentLength)
                    .isEqualTo(Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES));
            assertThat(safe.getExportBatches()).isEqualTo(2);
            assertThat(senders.states.getLast().actualLength).isEqualTo(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES);
            assertThat(safe.getExportFailures()).isZero();
            assertThat(safe.getDroppedSpans()).isZero();
            assertThat(safe.getOversizedSpansDropped()).isEqualTo(1);
            assertThat(safe.getExportedSpans()).isEqualTo(1);
            assertThat(capture.events()).filteredOn(event -> event.eventName().equals("SAFE_EXPORT_OVERSIZED_SPAN_DROPPED"))
                    .singleElement().satisfies(event -> {
                        assertThat(event.errorType()).isNull();
                        assertThat(event.throwableDigest()).isNull();
                    });
            assertThat(capture.events()).noneMatch(event -> event.eventName().equals("INVARIANT_VIOLATION"));
            assertThat(safe.export(List.of(span("next"))).isSuccess()).isTrue();
            assertThat(senders.states.getLast().sendCalls).isEqualTo(2);
            assertThat(safe.getExportBatches()).isEqualTo(3);
            assertThat(safe.getExportFailures()).isZero();
            assertThat(safe.getExportedSpans()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void closesCopyWhenOriginalShutdownFails(Protocol protocol) {
        // given
        SenderCapture senders = new SenderCapture();
        SpanExporter original = spy(exporter(protocol, senders, false));
        LinkageError failure = new LinkageError("Original exporter shutdown failed");
        doThrow(failure).when(original).shutdown();

        // when
        // then
        assertThatThrownBy(() -> OtlpRequestSizeCustomizer.customize(original, config(false)))
                .isSameAs(failure);
        assertThat(senders.states).hasSize(2);
        assertThat(senders.states.getLast().shutdownCalls).isEqualTo(1);
        senders.states.getFirst().shutdown();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void closesCopyWhenTimeoutConfigurationPreventsWrapperConstruction(Protocol protocol) {
        // given
        SenderCapture senders = new SenderCapture();
        SpanExporter original = exporter(protocol, senders, false);
        ConfigProperties invalid = DefaultConfigProperties.createFromMap(Map.of(
                OtelSdkPropertyNames.BSP_EXPORT_TIMEOUT, "0"));

        // when
        // then
        try (original) {
            assertThatThrownBy(() -> OtlpRequestSizeCustomizer.customize(original, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(senders.states).hasSize(2);
            assertThat(senders.states.getLast().shutdownCalls).isEqualTo(1);
        }
    }

    private static ConfigProperties config(boolean retryDisabled) {
        return DefaultConfigProperties.createFromMap(Map.of(
                OtelSdkPropertyNames.OTLP_RETRY_DISABLED, Boolean.toString(retryDisabled)));
    }

    private static SpanExporter exporter(Protocol protocol, SenderCapture senders, boolean retryDisabled) {
        RetryPolicy retry = retryDisabled ? null : RetryPolicy.getDefault();
        return protocol == Protocol.GRPC
                ? OtlpGrpcSpanExporter.builder().setComponentLoader(senders).setRetryPolicy(retry).build()
                : OtlpHttpSpanExporter.builder().setComponentLoader(senders).setRetryPolicy(retry).build();
    }

    private static SpanData span(String name) {
        return TestSpanData.builder().setName(name).setKind(SpanKind.INTERNAL).setStatus(StatusData.unset())
                .setStartEpochNanos(0).setEndEpochNanos(1).setHasEnded(true).build();
    }

    private static SpanData spanWithPayload(String payload) {
        return TestSpanData.builder().setName("payload").setKind(SpanKind.INTERNAL).setStatus(StatusData.unset())
                .setAttributes(Attributes.of(AttributeKey.stringKey("payload"), payload))
                .setStartEpochNanos(0).setEndEpochNanos(1).setHasEnded(true).build();
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private enum Protocol {
        GRPC, HTTP
    }

    private static final class SenderState {
        private int sendCalls;
        private int shutdownCalls;
        private int contentLength;
        private long actualLength;

        private void sent(MessageWriter writer) {
            sendCalls++;
            contentLength = writer.getContentLength();
            actualLength = 0;
            try {
                writer.writeMessage(new OutputStream() {
                    @Override
                    public void write(int value) {
                        actualLength++;
                    }

                    @Override
                    public void write(@Nonnull byte[] bytes, int offset, int length) {
                        actualLength += length;
                    }
                });
            } catch (IOException failure) {
                throw new AssertionError("Failed to serialize captured request", failure);
            }
        }

        private CompletableResultCode shutdown() {
            shutdownCalls++;
            return CompletableResultCode.ofSuccess();
        }
    }

    private static final class SenderCapture implements ComponentLoader {
        private final List<SenderState> states = new ArrayList<>();
        private final List<GrpcSenderConfig> grpcConfigs = new ArrayList<>();
        private final List<HttpSenderConfig> httpConfigs = new ArrayList<>();
        private final ComponentLoader delegate = ComponentLoader.forClassLoader(getClass().getClassLoader());

        @Override
        public <T> Iterable<T> load(Class<T> spiClass) {
            if (spiClass == GrpcSenderProvider.class) {
                return List.of(spiClass.cast(new ObservingGrpcSenderProvider(this::grpcSender)));
            }
            if (spiClass == HttpSenderProvider.class) {
                return List.of(spiClass.cast(new ObservingHttpSenderProvider(this::httpSender)));
            }
            return delegate.load(spiClass);
        }

        private GrpcSender grpcSender(GrpcSenderConfig config) {
            grpcConfigs.add(config);
            SenderState state = new SenderState();
            states.add(state);
            return new GrpcSender() {
                @Override
                public void send(MessageWriter writer, Consumer<GrpcResponse> onResponse, Consumer<Throwable> onError) {
                    state.sent(writer);
                    onResponse.accept(new GrpcResponse() {
                        @Override public GrpcStatusCode getStatusCode() { return GrpcStatusCode.OK; }
                        @Override public String getStatusDescription() { return null; }
                        @Override public byte[] getResponseMessage() { return new byte[0]; }
                    });
                }

                @Override
                public CompletableResultCode shutdown() {
                    return state.shutdown();
                }
            };
        }

        private HttpSender httpSender(HttpSenderConfig config) {
            httpConfigs.add(config);
            SenderState state = new SenderState();
            states.add(state);
            return new HttpSender() {
                @Override
                public void send(MessageWriter writer, Consumer<HttpResponse> onResponse, Consumer<Throwable> onError) {
                    state.sent(writer);
                    onResponse.accept(new HttpResponse() {
                        @Override public int getStatusCode() { return 200; }
                        @Override public String getStatusMessage() { return "OK"; }
                        @Override public byte[] getResponseBody() { return new byte[0]; }
                    });
                }

                @Override
                public CompletableResultCode shutdown() {
                    return state.shutdown();
                }
            };
        }
    }
}
