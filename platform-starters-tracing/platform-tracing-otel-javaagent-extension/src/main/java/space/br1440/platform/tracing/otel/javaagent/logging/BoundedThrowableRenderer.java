package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class BoundedThrowableRenderer {

    static final int MAX_NODES = 16;
    static final int MAX_CAUSES = 3;
    static final int MAX_SUPPRESSED = 3;
    static final int MAX_FRAMES = 30;
    static final int MAX_TOTAL_LENGTH = 4096;

    private static final int MAX_DIGEST_LENGTH = MAX_TOTAL_LENGTH + 23;

    private static final String ALREADY_VISITED = "... (already visited)";
    private static final String NODE_BUDGET_EXHAUSTED = "... (node budget exhausted)";
    private static final String CAUSE_BUDGET_EXHAUSTED = "... (cause chain truncated: cause edge budget exhausted)";
    private static final String SUPPRESSED_BUDGET_EXHAUSTED = "\n... (per-node suppression budget exhausted)";
    private static final String CHARACTER_BUDGET_EXHAUSTED = "\n... (character budget exhausted)";
    private static final String PRIMARY_CHAIN_RESERVATION_REASON = "character budget reserved for primary cause chain";
    private static final String RENDERING_FAILURE_PREFIX = " (detail rendering failed: ";
    private static final String NATIVE_METHOD = "Native Method";
    private static final String UNKNOWN_SOURCE = "Unknown Source";

    static int maxDigestChars() {
        return MAX_DIGEST_LENGTH;
    }

    public static String render(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        try {
            BoundedDigest out = new BoundedDigest();
            appendGraph(out, throwable);
            return out.finish();
        } catch (Throwable renderingFailure) {
            PlatformThrowables.propagateIfFatal(renderingFailure);
            return renderingFailureDetail(
                    throwable.getClass().getName(),
                    renderingFailure.getClass().getName()
            );
        }
    }

    static String renderFrame(StackTraceElement frame) {
        String location = frameLocation(frame.getFileName(), frame.getLineNumber());
        return frame.getClassName() + "." + frame.getMethodName() + "(" + location + ")";
    }

    static String renderingFailureDetail(String throwableClassName, String failureClassName) {
        int failureClassBudget = MAX_TOTAL_LENGTH / 2 - RENDERING_FAILURE_PREFIX.length() - 1;
        int failureClassLength = Math.min(failureClassName.length(), failureClassBudget);
        int failureClassBoundary = BoundedDigest.safeBoundary(failureClassName, failureClassLength);

        String requiredSuffix = RENDERING_FAILURE_PREFIX
                + failureClassName.substring(0, failureClassBoundary)
                + ")";

        BoundedDigest out = new BoundedDigest(
                requiredSuffix,
                failureClassBoundary < failureClassName.length()
        );
        out.append(throwableClassName);
        return out.finish();
    }

    private static void appendGraph(BoundedDigest out, Throwable root) {
        Set<Throwable> seen = newIdentitySet();
        PrimaryChain primaryChain = capturePrimaryChain(root);
        int[] primaryFrameAllocations = allocatePrimaryFrames(primaryChain.nodes());
        long[] primaryStructureReservations = primaryStructureReservations(primaryChain);
        Deque<TraversalItem> pending = new ArrayDeque<>();

        int renderedNodes = 0;
        int renderedFrames = 0;

        for (int i = 0; i < primaryChain.nodes().size() && !out.isExhausted(); i++) {
            ThrowableSnapshot snapshot = primaryChain.nodes().get(i);
            Relation relation = (i == 0) ? Relation.ROOT : Relation.CAUSE;
            out.append(relation.prefix());
            seen.add(snapshot.throwable());
            renderedNodes++;

            out.append(snapshot.throwable().getClass().getName());
            int framesToRender = Math.min(snapshot.frames().length, primaryFrameAllocations[i]);
            int appendedFrames = appendFrames(
                    out,
                    snapshot.frames(),
                    framesToRender,
                    primaryStructureReservations[i]
            );
            renderedFrames += appendedFrames;

            if (!out.isExhausted() && snapshot.frames().length > appendedFrames) {
                int omittedFrames = snapshot.frames().length - appendedFrames;
                if (appendedFrames < framesToRender) {
                    appendFrameOmissionMarker(
                            out,
                            omittedFrames,
                            PRIMARY_CHAIN_RESERVATION_REASON,
                            primaryStructureReservations[i]
                    );
                } else {
                    appendFrameOmissionMarker(
                            out,
                            omittedFrames,
                            "total frame budget exhausted",
                            primaryStructureReservations[i]
                    );
                }
            }
        }

        if (!out.isExhausted() && primaryChain.terminalMarker() != null) {
            out.append(Relation.CAUSE.prefix());
            out.append(primaryChain.terminalMarker());
        }

        for (int i = primaryChain.nodes().size() - 1; i >= 0; i--) {
            pushSuppressed(pending, primaryChain.nodes().get(i).suppressed());
        }

        while (!pending.isEmpty() && !out.isExhausted()) {
            TraversalItem item = pending.pop();
            if (item.marker() != null) {
                out.append(item.marker());
                continue;
            }

            out.append(item.relation().prefix());
            if (out.isExhausted()) {
                break;
            }

            Throwable current = item.throwable();
            if (!seen.add(current)) {
                out.append(ALREADY_VISITED);
                continue;
            }

            if (renderedNodes == MAX_NODES) {
                out.append(NODE_BUDGET_EXHAUSTED);
                break;
            }
            renderedNodes++;

            out.append(current.getClass().getName());
            if (out.isExhausted()) {
                break;
            }

            StackTraceElement[] frames = current.getStackTrace();
            int availableFrames = MAX_FRAMES - renderedFrames;
            int framesToRender = Math.min(frames.length, availableFrames);
            renderedFrames += appendFrames(out, frames, framesToRender, 0);

            if (out.isExhausted()) {
                break;
            }

            if (frames.length > framesToRender) {
                appendFrameOmissionMarker(
                        out,
                        frames.length - framesToRender,
                        "total frame budget exhausted",
                        0
                );
            }

            if (out.isExhausted()) {
                break;
            }

            Throwable cause = current.getCause();
            Throwable[] suppressed = current.getSuppressed();
            pushSuppressed(pending, suppressed);
            if (cause != null) {
                if (item.causeDepth() == MAX_CAUSES) {
                    pending.push(TraversalItem.marker(
                            Relation.CAUSE.prefix() + CAUSE_BUDGET_EXHAUSTED
                    ));
                } else {
                    pending.push(TraversalItem.node(
                            cause,
                            Relation.CAUSE,
                            item.causeDepth() + 1
                    ));
                }
            }
        }
    }

    private static PrimaryChain capturePrimaryChain(Throwable root) {
        List<ThrowableSnapshot> nodes = new ArrayList<>(MAX_CAUSES + 1);
        Set<Throwable> seen = newIdentitySet();
        Throwable current = root;

        for (int causeDepth = 0; ; causeDepth++) {
            seen.add(current);
            nodes.add(new ThrowableSnapshot(
                    current,
                    current.getStackTrace(),
                    current.getSuppressed()
            ));

            Throwable cause = current.getCause();
            if (cause == null) {
                return new PrimaryChain(nodes, null);
            }

            if (seen.contains(cause)) {
                return new PrimaryChain(nodes, ALREADY_VISITED);
            }

            if (causeDepth == MAX_CAUSES) {
                return new PrimaryChain(nodes, CAUSE_BUDGET_EXHAUSTED);
            }

            current = cause;
        }
    }

    private static int[] allocatePrimaryFrames(List<ThrowableSnapshot> nodes) {
        int[] allocations = new int[nodes.size()];
        int remaining = MAX_FRAMES;
        boolean allocated;

        do {
            allocated = false;
            for (int i = 0; i < nodes.size() && remaining > 0; i++) {
                if (allocations[i] < nodes.get(i).frames().length) {
                    allocations[i]++;
                    remaining--;
                    allocated = true;
                }
            }
        } while (remaining > 0 && allocated);

        return allocations;
    }

    private static long[] primaryStructureReservations(PrimaryChain primaryChain) {
        List<ThrowableSnapshot> nodes = primaryChain.nodes();
        long[] reservations = new long[nodes.size()];
        long reserved = 0;
        if (primaryChain.terminalMarker() != null) {
            reserved = Relation.CAUSE.prefix().length() + primaryChain.terminalMarker().length();
        }

        for (int i = nodes.size() - 1; i >= 0; i--) {
            reservations[i] = reserved;
            if (i > 0) {
                reserved += Relation.CAUSE.prefix().length();
                reserved += nodes.get(i).throwable().getClass().getName().length();
            }
        }
        return reservations;
    }

    private static int appendFrames(BoundedDigest out,
                                    StackTraceElement[] frames,
                                    int framesToRender,
                                    long reservedCharacters) {
        int rendered = 0;
        while (rendered < framesToRender && !out.isExhausted()) {
            if (!out.canAppendWithoutTruncation(frameLength(frames[rendered]), reservedCharacters)) {
                if (reservedCharacters > 0) {
                    break;
                }
            }

            appendFrame(out, frames[rendered]);
            rendered++;
        }

        return rendered;
    }

    private static long frameLength(StackTraceElement frame) {
        long length = "\n\tat ".length();
        length += frame.getClassName().length();
        length += 1;
        length += frame.getMethodName().length();
        length += 2;

        if (frame.getLineNumber() == -2) {
            return length + NATIVE_METHOD.length();
        }

        if (frame.getFileName() == null || frame.getLineNumber() < 0) {
            return length + UNKNOWN_SOURCE.length();
        }

        return length
                + frame.getFileName().length()
                + 1
                + Integer.toString(frame.getLineNumber()).length();
    }

    private static void appendFrameOmissionMarker(BoundedDigest out,
                                                  int omittedFrames,
                                                  String reason,
                                                  long reservedCharacters) {
        String marker = "\n\t... %d more (%s)".formatted(omittedFrames, reason);
        if (reservedCharacters == 0
                || out.canAppendWithoutTruncation(marker.length(), reservedCharacters)) {
            out.append(marker);
        }
    }

    private static void pushSuppressed(Deque<TraversalItem> pending, Throwable[] suppressed) {
        int suppressedToTraverse = Math.min(suppressed.length, MAX_SUPPRESSED);
        if (suppressed.length > suppressedToTraverse) {
            pending.push(TraversalItem.marker(SUPPRESSED_BUDGET_EXHAUSTED));
        }
        for (int i = suppressedToTraverse - 1; i >= 0; i--) {
            pending.push(TraversalItem.node(suppressed[i], Relation.SUPPRESSED, 0));
        }
    }

    private static void appendFrame(BoundedDigest out, StackTraceElement frame) {
        out.append("\n\tat ");
        out.append(frame.getClassName());
        out.append(".");
        out.append(frame.getMethodName());
        out.append("(");
        appendFrameLocation(out, frame.getFileName(), frame.getLineNumber());
        out.append(")");
    }

    private static void appendFrameLocation(BoundedDigest out, String fileName, int lineNumber) {
        if (lineNumber == -2) {
            out.append(NATIVE_METHOD);
            return;
        }

        if (fileName == null || lineNumber < 0) {
            out.append(UNKNOWN_SOURCE);
            return;
        }

        out.append(fileName);
        out.append(":");
        out.append(Integer.toString(lineNumber));
    }

    private static String frameLocation(String fileName, int lineNumber) {
        if (lineNumber == -2) {
            return NATIVE_METHOD;
        }

        if (fileName == null || lineNumber < 0) {
            return UNKNOWN_SOURCE;
        }

        return fileName + ":" + lineNumber;
    }

    private static Set<Throwable> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private enum Relation {
        ROOT(""),
        CAUSE("\ncaused by "),
        SUPPRESSED("\nsuppressed ");

        private final String prefix;

        Relation(String prefix) {
            this.prefix = prefix;
        }

        String prefix() {
            return prefix;
        }
    }

    private record PrimaryChain(List<ThrowableSnapshot> nodes, String terminalMarker) {
    }

    private record ThrowableSnapshot(Throwable throwable,
                                     StackTraceElement[] frames,
                                     Throwable[] suppressed) {
    }

    private record TraversalItem(Throwable throwable,
                                 Relation relation,
                                 String marker,
                                 int causeDepth) {
        static TraversalItem node(Throwable throwable, Relation relation, int causeDepth) {
            return new TraversalItem(throwable, relation, null, causeDepth);
        }

        static TraversalItem marker(String marker) {
            return new TraversalItem(null, null, marker, 0);
        }
    }

    private static final class BoundedDigest {

        private final StringBuilder value = new StringBuilder(256);
        private final String requiredSuffix;
        private final boolean requiredSuffixTruncated;
        private boolean exhausted;
        private boolean completed;

        private BoundedDigest() {
            this("", false);
        }

        private BoundedDigest(String requiredSuffix, boolean requiredSuffixTruncated) {
            this.requiredSuffix = requiredSuffix;
            this.requiredSuffixTruncated = requiredSuffixTruncated;
        }

        void append(String addition) {
            if (exhausted || completed) {
                return;
            }

            int normalContentLimit = MAX_TOTAL_LENGTH - requiredSuffix.length();
            if (!requiredSuffixTruncated
                    && addition.length() <= normalContentLimit - value.length()) {
                value.append(addition);
                return;
            }

            int contentLimit = maxDigestChars() - CHARACTER_BUDGET_EXHAUSTED.length() - requiredSuffix.length();
            if (value.length() > contentLimit) {
                value.setLength(safeBoundary(value, contentLimit));
            } else {
                int additionLength = Math.min(addition.length(), contentLimit - value.length());
                value.append(addition, 0, safeBoundary(addition, additionLength));
            }
            value.append(CHARACTER_BUDGET_EXHAUSTED);
            value.append(requiredSuffix);
            exhausted = true;
            completed = true;
        }

        boolean isExhausted() {
            return exhausted;
        }

        boolean canAppendWithoutTruncation(long additionLength, long reservedCharacters) {
            if (exhausted || completed) {
                return false;
            }
            long normalContentLimit = MAX_TOTAL_LENGTH - requiredSuffix.length();
            return additionLength + reservedCharacters <= normalContentLimit - value.length();
        }

        String finish() {
            if (!completed) {
                value.append(requiredSuffix);
                completed = true;
            }
            return value.toString();
        }

        static int safeBoundary(CharSequence sequence, int boundary) {
            if (boundary > 0
                    && boundary < sequence.length()
                    && Character.isHighSurrogate(sequence.charAt(boundary - 1))
                    && Character.isLowSurrogate(sequence.charAt(boundary))) {
                return boundary - 1;
            }

            return boundary;
        }
    }
}
