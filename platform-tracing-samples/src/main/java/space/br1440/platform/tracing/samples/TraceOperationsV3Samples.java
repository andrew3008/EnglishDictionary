package space.br1440.platform.tracing.samples;

import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;

import java.util.List;

/**
 * Компилируемые примеры v3 API, на которые ссылаются {@code docs/tracing/platform-tracing-v3-*.md}.
 * <p>
 * Этот класс — documentation-as-code: он не выполняется в production и не требует
 * external services. В runtime передайте {@link TraceOperations} bean или test double.
 */
public final class TraceOperationsV3Samples {

    private final TraceOperations traceOperations;

    public TraceOperationsV3Samples(@Nonnull TraceOperations traceOperations) {
        this.traceOperations = traceOperations;
    }

    /** {@code traceContext().traceId()} для logging и correlation. */
    public String currentTraceIdForLogging() {
        return traceOperations.traceContext()
                .traceId()
                .orElse("unknown");
    }

    /** Стандартная manual operation с void body. */
    public void recalculatePricing(long orderId, PricingService pricingService) {
        traceOperations.spans()
                .operation("recalculate-pricing")
                .run(() -> pricingService.recalculate(orderId));
    }

    /** Manual operation, возвращающая значение. */
    public Price calculatePrice(long orderId, PricingService pricingService) {
        return traceOperations.spans()
                .operation("calculate-price")
                .call(() -> pricingService.calculate(orderId));
    }

    /** Manual operation с checked exception. */
    public Order loadOrder(long orderId, OrderRepository repository) throws Exception {
        return traceOperations.spans()
                .operation("load-order")
                .callChecked(() -> repository.load(orderId));
    }

    /** Явный ROOT span для scheduled/background work без active parent. */
    public void runScheduledReconciliation(ReconciliationService service) {
        traceOperations.spans()
                .operation("nightly-reconciliation")
                .root()
                .run(service::reconcileAll);
    }

    /** DETACHED span без links (разрешённая topology). */
    public void runDetachedAudit(AuditService auditService) {
        traceOperations.spans()
                .operation("compliance-audit")
                .detached()
                .run(auditService::runOnce);
    }

    /** Database semantic builder под {@code spans().transport().database()}. */
    public List<Order> queryOrders(OrderRepository repository) {
        return traceOperations.spans()
                .transport()
                .database()
                .system("postgresql")
                .operation("SELECT")
                .collection("orders")
                .call(repository::findAll);
    }

    /** RPC client semantic builder под {@code spans().transport().rpc().client()}. */
    public Order fetchOrderViaRpc(OrderRpcClient client, long orderId) {
        return traceOperations.spans()
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
     * Kafka batch consumer: ROOT + pre-start links (основной public links example).
     * {@code messageContexts} обычно извлекаются из record headers через OTel propagator.
     */
    public void processKafkaBatch(
            List<RemoteSpanLink> messageContexts,
            BatchProcessor processor,
            List<Record> records) {
        traceOperations.spans()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .linkedTo(messageContexts.toArray(RemoteSpanLink[]::new))
                .run(() -> processor.processBatch(records));
    }

    /** Альтернативные batch links через W3C traceparent strings (tracestate требует отдельный header и здесь не восстанавливается). */
    public void processKafkaBatchFromTraceparents(
            List<String> traceparents,
            BatchProcessor processor,
            List<Record> records) {
        traceOperations.spans()
                .transport()
                .kafka()
                .consumer()
                .batch("orders")
                .root()
                .fromTraceparent(traceparents.toArray(String[]::new))
                .run(() -> processor.processBatch(records));
    }

    /** Governed escape hatch: {@code fromSpec} с обязательными reason и reference. */
    public void legacyIntegrationCall(LegacyClient legacyClient) {
        SpanSpec spec = SpanSpec.builder("legacy-bridge-call")
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.LEGACY_INTEGRATION)
                .reference("PLATFORM-TRACING-1234")
                .attribute("integration.vendor", "acme")
                .build();

        traceOperations.spans()
                .fromSpec(spec)
                .run(legacyClient::invoke);
    }

    /** {@code TEMPORARY_WORKAROUND} требует non-blank reference. */
    public void temporaryWorkaround(TemporaryClient client) {
        SpanSpec spec = SpanSpec.builder("vendor-sdk-workaround")
                .category(SpanCategory.INTERNAL)
                .child()
                .reason(SpanSpecReason.TEMPORARY_WORKAROUND)
                .reference("JIRA-5678")
                .build();

        traceOperations.spans()
                .fromSpec(spec)
                .call(client::callOnce);
    }

    // --- Stub types (без external dependencies) ---

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
