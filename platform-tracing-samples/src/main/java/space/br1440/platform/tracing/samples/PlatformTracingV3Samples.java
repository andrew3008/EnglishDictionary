package space.br1440.platform.tracing.samples;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.api.propagation.TraceparentParser;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;

import java.util.List;

/**
 * Compilable v3 API examples referenced by {@code docs/tracing/platform-tracing-v3-*.md}.
 * <p>
 * This class is documentation-as-code: it is not executed in production and does not require
 * external services. Inject a {@link PlatformTracing} bean (or test double) at runtime.
 */
public final class PlatformTracingV3Samples {

    private final PlatformTracing platformTracing;

    public PlatformTracingV3Samples(@Nonnull PlatformTracing platformTracing) {
        this.platformTracing = platformTracing;
    }

    /** {@code traceContext().traceId()} for logging and correlation. */
    public String currentTraceIdForLogging() {
        return platformTracing.traceContext()
                .traceId()
                .orElse("unknown");
    }

    /** Standard manual operation with void body. */
    public void recalculatePricing(long orderId, PricingService pricingService) {
        platformTracing.manual()
                .operation("recalculate-pricing")
                .run(() -> pricingService.recalculate(orderId));
    }

    /** Manual operation returning a value. */
    public Price calculatePrice(long orderId, PricingService pricingService) {
        return platformTracing.manual()
                .operation("calculate-price")
                .call(() -> pricingService.calculate(orderId));
    }

    /** Manual operation with checked exception. */
    public Order loadOrder(long orderId, OrderRepository repository) throws Exception {
        return platformTracing.manual()
                .operation("load-order")
                .callChecked(() -> repository.load(orderId));
    }

    /** Explicit ROOT span for scheduled or background work without an active parent. */
    public void runScheduledReconciliation(ReconciliationService service) {
        platformTracing.manual()
                .operation("nightly-reconciliation")
                .root()
                .run(service::reconcileAll);
    }

    /** DETACHED span without links (allowed topology). */
    public void runDetachedAudit(AuditService auditService) {
        platformTracing.manual()
                .operation("compliance-audit")
                .detached()
                .run(auditService::runOnce);
    }

    /** Database semantic builder under {@code manual().transport().database()}. */
    public List<Order> queryOrders(OrderRepository repository) {
        return platformTracing.manual()
                .transport()
                .database()
                .system("postgresql")
                .operation("SELECT")
                .collection("orders")
                .call(repository::findAll);
    }

    /** RPC client semantic builder under {@code manual().transport().rpc().client()}. */
    public Order fetchOrderViaRpc(OrderRpcClient client, long orderId) {
        return platformTracing.manual()
                .transport()
                .rpc()
                .client()
                .system("grpc")
                .service("orders.OrderService")
                .method("GetOrder")
                .serverAddress("orders.example.com")
                .call(() -> client.getOrder(orderId));
    }

    /**
     * Kafka batch consumer: ROOT + pre-start links (primary public links example).
     * {@code messageContexts} would normally be extracted from record headers via OTel propagator.
     */
    public void processKafkaBatch(
            List<RemoteSpanLink> messageContexts,
            BatchProcessor processor,
            List<Record> records) {
        platformTracing.manual()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .linkedTo(messageContexts.toArray(RemoteSpanLink[]::new))
                .run(() -> processor.processBatch(records));
    }

    /** Alternative batch links via W3C traceparent strings (tracestate preserved when present). */
    public void processKafkaBatchFromTraceparents(
            List<String> traceparents,
            BatchProcessor processor,
            List<Record> records) {
        platformTracing.manual()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .fromTraceparent(traceparents.toArray(String[]::new))
                .run(() -> processor.processBatch(records));
    }

    /** Governed escape hatch: {@code spanFromSpec} with mandatory reason and reference. */
    public void legacyIntegrationCall(LegacyClient legacyClient) {
        SpanSpec spec = SpanSpec.builder("legacy-bridge-call")
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .reference("PLATFORM-TRACING-1234")
                .attribute("integration.vendor", "acme")
                .build();

        platformTracing.manual()
                .spanFromSpec(spec)
                .run(legacyClient::invoke);
    }

    /** {@code TEMPORARY_WORKAROUND} requires a non-blank reference. */
    public void temporaryWorkaround(TemporaryClient client) {
        SpanSpec spec = SpanSpec.builder("vendor-sdk-workaround")
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.TEMPORARY_WORKAROUND)
                .reference("JIRA-5678")
                .build();

        platformTracing.manual()
                .spanFromSpec(spec)
                .call(client::callOnce);
    }

    /** Example of lenient traceparent parsing for batch header extraction loops. */
    public List<RemoteSpanLink> extractLinksFromHeaders(List<String> traceparentHeaders) {
        return traceparentHeaders.stream()
                .map(TraceparentParser::parseTraceparent)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    // --- Stub types (no external dependencies) ---

    public interface PricingService {
        void recalculate(long orderId);

        Price calculate(long orderId);
    }

    public interface OrderRepository {
        Order load(long orderId) throws Exception;

        List<Order> findAll();
    }

    public interface ReconciliationService {
        void reconcileAll();
    }

    public interface AuditService {
        void runOnce();
    }

    public interface OrderRpcClient {
        Order getOrder(long orderId);
    }

    public interface BatchProcessor {
        void processBatch(List<Record> records);
    }

    public interface LegacyClient {
        void invoke();
    }

    public interface TemporaryClient {
        String callOnce();
    }

    public record Price(long orderId, java.math.BigDecimal amount) {
    }

    public record Order(long id, String status) {
    }

    public record Record(String topic, int partition, long offset) {
    }
}
