package space.br1440.platform.tracing.otel.javaagent.processor.composite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import space.br1440.platform.tracing.otel.javaagent.processor.ProcessorDiagnosticIdentity;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformEndingSpanProcessor;

import io.opentelemetry.sdk.trace.SpanProcessor;

final class DelegateWiring {

    private static final Pattern DIAGNOSTIC_ID_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");

    private final List<NamedDelegate> delegates;
    private final NamedDelegate[] startDelegates;
    private final NamedDelegate[] endDelegates;
    private final NamedEndingDelegate[] onEndingDelegates;

    private DelegateWiring(List<NamedDelegate> delegates,
                           NamedDelegate[] startDelegates,
                           NamedDelegate[] endDelegates,
                           NamedEndingDelegate[] onEndingDelegates) {
        this.delegates = delegates;
        this.startDelegates = startDelegates;
        this.endDelegates = endDelegates;
        this.onEndingDelegates = onEndingDelegates;
    }

    static DelegateWiring wire(List<? extends SpanProcessor> rawDelegates) {
        List<NamedDelegate> wrapped = new ArrayList<>(rawDelegates.size());
        List<NamedDelegate> startParticipants = new ArrayList<>();
        List<NamedDelegate> endParticipants = new ArrayList<>();
        List<NamedEndingDelegate> onEndingParticipants = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        for (SpanProcessor delegate : rawDelegates) {
            Objects.requireNonNull(delegate, "delegate");

            String name = resolveDelegateName(delegate);
            if (!seenNames.add(name)) {
                throw new IllegalArgumentException("Duplicate processor diagnostic ID: " + name);
            }

            NamedDelegate namedDelegate = new NamedDelegate(name, delegate, new AtomicLong());
            wrapped.add(namedDelegate);

            if (delegate.isStartRequired()) {
                startParticipants.add(namedDelegate);
            }

            if (delegate.isEndRequired()) {
                endParticipants.add(namedDelegate);
            }

            PlatformEndingSpanProcessor endingDelegate = PlatformEndingSpanProcessor.adapt(delegate);
            if (endingDelegate != null && endingDelegate.isOnEndingRequired()) {
                onEndingParticipants.add(new NamedEndingDelegate(namedDelegate, endingDelegate));
            }
        }

        return new DelegateWiring(
                List.copyOf(wrapped),
                startParticipants.toArray(new NamedDelegate[0]),
                endParticipants.toArray(new NamedDelegate[0]),
                onEndingParticipants.toArray(new NamedEndingDelegate[0])
        );
    }

    List<NamedDelegate> delegates() {
        return delegates;
    }

    NamedDelegate[] startDelegates() {
        return startDelegates;
    }

    NamedDelegate[] endDelegates() {
        return endDelegates;
    }

    NamedEndingDelegate[] onEndingDelegates() {
        return onEndingDelegates;
    }

    private static String resolveDelegateName(SpanProcessor delegate) {
        if (!(delegate instanceof ProcessorDiagnosticIdentity identity)) {
            throw new IllegalArgumentException("Processor does not declare internal diagnostic identity: " + delegate.getClass().getName());
        }

        String diagnosticId = identity.diagnosticId();
        if ((diagnosticId == null) || !DIAGNOSTIC_ID_PATTERN.matcher(diagnosticId).matches()) {
            throw new IllegalArgumentException("Invalid processor diagnostic ID: " + diagnosticId);
        }

        return diagnosticId;
    }
}
