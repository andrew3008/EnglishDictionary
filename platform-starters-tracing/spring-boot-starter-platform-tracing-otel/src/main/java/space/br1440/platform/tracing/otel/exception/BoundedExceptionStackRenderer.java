package space.br1440.platform.tracing.otel.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Рендерит цепочку cause в пределах {@code characterBudget}.
 *
 * <p>Строки используют словарь {@link Throwable#printStackTrace()} ({@code Caused by:}, {@code \tat},
 * перевод строки {@code \n}), но текст не является выводом {@code printStackTrace}: подавленные
 * исключения и общие кадры ({@code \t... N more}) не печатаются, сообщение уровня берётся только
 * из {@code messageSanitizer}, циклическая ссылка содержит {@code Class.getName()}, а не
 * {@code Throwable.toString()}.
 *
 * <p>Обходятся исключение и не больше {@link #MAX_CAUSED_BY_LEVELS} causes. Повтор того же
 * экземпляра завершает обход строкой {@code Caused by: [CIRCULAR REFERENCE: <Class.getName()>]};
 * {@code getCause}, {@code getStackTrace} и {@code messageSanitizer} для этого повторного
 * экземпляра не вызываются. Если после лимита causes остаются ещё causes, обход завершается
 * строкой {@code ... (further causes omitted)}.
 */
final class BoundedExceptionStackRenderer {

    static final int MAX_FRAMES = 32;
    static final int MAX_CAUSED_BY_LEVELS = 10;
    static final int DEFAULT_CHARACTER_BUDGET = 1_000;
    static final String BUDGET_EXHAUSTED = "... (character budget exhausted)";
    static final String FURTHER_CAUSES_OMITTED = "... (further causes omitted)";

    private static final String CAUSED_BY = "Caused by: ";
    private static final String FRAME_PREFIX = "\tat ";
    private static final char LINE_END = '\n';
    private static final StackTraceElement[] NO_FRAMES = new StackTraceElement[0];

    private final int characterBudget;
    private final Function<Throwable, String> messageSanitizer;

    BoundedExceptionStackRenderer(int characterBudget, @Nonnull Function<Throwable, String> messageSanitizer) {
        if (characterBudget < 1) {
            throw new IllegalArgumentException("characterBudget must be positive");
        }

        this.characterBudget = characterBudget;
        this.messageSanitizer = Objects.requireNonNull(messageSanitizer, "messageSanitizer");
    }

    @Nonnull
    RenderedExceptionStack render(@Nonnull Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");

        Chain chain = collect(throwable);
        if (chain.naturalCost() <= characterBudget) {
            return new RenderedExceptionStack(write(chain.levels, chain.trailer, false), chain.limited());
        }

        int bodyBudget = characterBudget - lineCost(BUDGET_EXHAUSTED);
        if (bodyBudget <= 0) {
            return new RenderedExceptionStack((BUDGET_EXHAUSTED + LINE_END).substring(0, characterBudget), true);
        }

        List<Level> levels = fitHeaders(chain.levels, bodyBudget);
        int remaining = bodyBudget - headerCost(levels);

        String trailer = chain.trailer;
        if ((trailer != null) && (lineCost(trailer) <= remaining)) {
            remaining -= lineCost(trailer);
        } else {
            trailer = null;
        }

        for (int index = (levels.size() - 1); index >= 0; index--) {
            remaining -= levels.get(index).fitFrames(remaining);
        }

        String stack = write(levels, trailer, true);
        return new RenderedExceptionStack(stack, true);
    }

    private Chain collect(Throwable root) {
        List<Level> levels = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(root);

        Throwable current = root;
        while (true) {
            Level level = new Level(current, !levels.isEmpty(), messageSanitizer.apply(current));
            levels.add(level);

            Throwable cause = current.getCause();
            if (cause == null) {
                return new Chain(levels, null, false);
            }

            if (levels.size() > MAX_CAUSED_BY_LEVELS) {
                return new Chain(levels, FURTHER_CAUSES_OMITTED, true);
            }

            if (!seen.add(cause)) {
                String trailer = CAUSED_BY + "[CIRCULAR REFERENCE: " + cause.getClass().getName() + "]";
                return new Chain(levels, trailer, false);
            }

            current = cause;
        }
    }

    private static List<Level> fitHeaders(List<Level> levels, int bodyBudget) {
        for (Level level : levels) {
            if (headerCost(levels) <= bodyBudget) {
                break;
            }

            level.dropMessage();
        }

        while ((levels.size() > 1) && (headerCost(levels) > bodyBudget)) {
            levels.removeFirst();
        }

        if (headerCost(levels) > bodyBudget) {
            levels.getFirst().truncateHeader(bodyBudget - 1);
        }

        return levels;
    }

    private static String write(List<Level> levels, @Nullable String trailer, boolean budgetExhausted) {
        StringBuilder out = new StringBuilder();
        for (Level level : levels) {
            level.writeTo(out);
        }

        if (trailer != null) {
            out.append(trailer).append(LINE_END);
        }

        if (budgetExhausted) {
            out.append(BUDGET_EXHAUSTED).append(LINE_END);
        }

        return out.toString();
    }

    private static int headerCost(List<Level> levels) {
        int cost = 0;
        for (Level level : levels) {
            cost += level.headerCost();
        }

        return cost;
    }

    private static int lineCost(String line) {
        return line.length() + 1;
    }

    static String framesOmitted(int omitted) {
        return "... (" + omitted + " frames omitted)";
    }

    private record Chain(List<Level> levels, @Nullable String trailer, boolean causeTruncated) {

        int naturalCost() {
            int cost = headerCost(levels);
            for (Level level : levels) {
                cost += level.frameCost();
            }

            return (trailer == null) ? cost : cost + lineCost(trailer);
        }

        boolean limited() {
            if (causeTruncated) {
                return true;
            }

            for (Level level : levels) {
                if (level.omittedFrames() > 0) {
                    return true;
                }
            }

            return false;
        }
    }

    private static final class Level {

        private final String headerWithoutMessage;
        private final String[] frameLines;
        private final int[] cumulativeFrameCost;
        private final int totalFrames;

        private String header;
        private int shownFrames;
        private boolean omittedMarkerShown;

        Level(Throwable throwable, boolean causedBy, @Nullable String message) {
            StackTraceElement[] frames = throwable.getStackTrace();
            if (frames == null) {
                frames = NO_FRAMES;
            }

            this.headerWithoutMessage = (causedBy ? CAUSED_BY : "") + throwable.getClass().getName();
            this.header = (message == null) ? headerWithoutMessage : headerWithoutMessage + ": " + message;
            this.totalFrames = frames.length;
            this.shownFrames = Math.min(MAX_FRAMES, frames.length);
            this.omittedMarkerShown = totalFrames > shownFrames;
            this.frameLines = new String[shownFrames];
            this.cumulativeFrameCost = new int[shownFrames + 1];
            for (int index = 0; index < shownFrames; index++) {
                frameLines[index] = FRAME_PREFIX + frames[index];
                cumulativeFrameCost[index + 1] = cumulativeFrameCost[index] + lineCost(frameLines[index]);
            }
        }

        int headerCost() {
            return lineCost(header);
        }

        void dropMessage() {
            header = headerWithoutMessage;
        }

        void truncateHeader(int room) {
            if (header.length() > room) {
                header = header.substring(0, room);
            }
        }

        int omittedFrames() {
            return totalFrames - shownFrames;
        }

        int frameCost() {
            return frameCost(shownFrames);
        }

        private int frameCost(int shown) {
            int omitted = totalFrames - shown;
            return cumulativeFrameCost[shown] + ((omitted > 0) ? lineCost(framesOmitted(omitted)) : 0);
        }

        int fitFrames(int remaining) {
            for (int shown = frameLines.length; shown >= 0; shown--) {
                int cost = frameCost(shown);
                if (cost <= remaining) {
                    shownFrames = shown;
                    omittedMarkerShown = totalFrames > shown;
                    return cost;
                }
            }

            shownFrames = 0;
            omittedMarkerShown = false;
            return 0;
        }

        void writeTo(StringBuilder out) {
            if (header.isEmpty() && (shownFrames == 0) && !omittedMarkerShown) {
                return;
            }

            out.append(header).append(LINE_END);
            for (int index = 0; index < shownFrames; index++) {
                out.append(frameLines[index]).append(LINE_END);
            }

            if (omittedMarkerShown) {
                out.append(framesOmitted(totalFrames - shownFrames)).append(LINE_END);
            }
        }
    }
}
