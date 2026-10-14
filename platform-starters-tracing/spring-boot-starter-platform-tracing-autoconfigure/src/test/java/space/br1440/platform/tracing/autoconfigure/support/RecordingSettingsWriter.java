package space.br1440.platform.tracing.autoconfigure.support;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsWriter;

public final class RecordingSettingsWriter implements TracingSettingsWriter {

    private final Supplier<Optional<AcceptedSettings>> currentSupplier;
    private final ApplyBehavior applyBehavior;
    private final Queue<SettingsDelivery> scriptedDeliveries = new ConcurrentLinkedQueue<>();
    private final List<ApplyCall> calls = new CopyOnWriteArrayList<>();
    private final SettingsDelivery defaultDelivery = delivery(TracingSettingsDeliveryResult.APPLIED);

    public RecordingSettingsWriter(Supplier<Optional<AcceptedSettings>> currentSupplier) {
        this(currentSupplier, null);
    }

    public RecordingSettingsWriter(Supplier<Optional<AcceptedSettings>> currentSupplier,
                                   ApplyBehavior applyBehavior) {
        this.currentSupplier = Objects.requireNonNull(currentSupplier, "currentSupplier");
        this.applyBehavior = applyBehavior;
    }

    public static RecordingSettingsWriter recordingSettingsWriter(ApplyBehavior applyBehavior,
                                                                  Supplier<Optional<AcceptedSettings>> currentSupplier) {
        return new RecordingSettingsWriter(currentSupplier, Objects.requireNonNull(applyBehavior, "applyBehavior"));
    }

    @Override
    public Optional<AcceptedSettings> current() {
        return Objects.requireNonNull(currentSupplier.get(), "currentSupplier result");
    }

    @Override
    public SettingsDelivery applySnapshot(SettingsRevision expectedBase,
                                          CompleteTracingSettings snapshot,
                                          SettingsWriter writer) {
        ApplyCall call = new ApplyCall(expectedBase, snapshot, writer);
        calls.add(call);

        if (applyBehavior != null) {
            return Objects.requireNonNull(
                    applyBehavior.applySnapshot(expectedBase, snapshot, writer),
                    "applyBehavior result"
            );
        }

        SettingsDelivery scripted = scriptedDeliveries.poll();
        return (scripted == null) ? defaultDelivery : scripted;
    }

    public int applyCallCount() {
        return calls.size();
    }

    public static SettingsDelivery delivery(TracingSettingsDeliveryResult outcome) {
        Objects.requireNonNull(outcome, "outcome");

        String messageCode = switch (outcome) {
            case APPLIED, UNCHANGED -> "NONE";
            case REJECTED -> "INVALID_VALUE";
            case STALE -> "ACCEPTED_SEQUENCE_MISMATCH";
            case FAILED -> "INTERNAL_FAILURE";
        };
        return new SettingsDelivery(UUID.randomUUID().toString(), outcome, messageCode);
    }

    @FunctionalInterface
    public interface ApplyBehavior {

        SettingsDelivery applySnapshot(SettingsRevision expectedBase,
                                       CompleteTracingSettings snapshot,
                                       SettingsWriter writer);
    }

    public record ApplyCall(SettingsRevision expectedBase,
                            CompleteTracingSettings snapshot,
                            SettingsWriter writer) {

        public ApplyCall {
            Objects.requireNonNull(expectedBase, "expectedBase");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(writer, "writer");
        }
    }
}
