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
 * из переданного сообщения корневого исключения и {@code messageSanitizer} для causes,
 * циклическая ссылка содержит {@code Class.getName()}, а не
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
    String render(@Nonnull Throwable throwable, @Nullable String rootMessage) {
        Objects.requireNonNull(throwable, "throwable");

        if ((characterBudget <= lineCost(BUDGET_EXHAUSTED))
                && (throwable.getClass().getName().length() >= characterBudget)) {
            return exhaustedPrefix();
        }

        Chain chain = collect(throwable, rootMessage);
        String natural = renderNatural(chain);
        if (natural != null) {
            return natural;
        }

        int bodyBudget = characterBudget - lineCost(BUDGET_EXHAUSTED);
        if (bodyBudget <= 0) {
            return exhaustedPrefix();
        }

        List<Level> levels = chain.levels;
        int remaining = fitHeaders(levels, bodyBudget);

        String trailer = chain.trailer;
        if ((trailer != null) && (lineCost(trailer) <= remaining)) {
            remaining -= lineCost(trailer);
        } else {
            trailer = null;
        }

        for (int index = (levels.size() - 1); index >= 0; index--) {
            Level level = levels.get(index);
            String renderedFrames = Objects.requireNonNull(level.renderFrames(remaining, false), "frames");
            level.selectedFrames = renderedFrames;
            remaining -= renderedFrames.length();
        }

        StringBuilder out = new StringBuilder();
        for (Level level : levels) {
            if (!level.selectedHeader.isEmpty()) {
                out.append(level.selectedHeader).append(LINE_END);
            }
            out.append(level.selectedFrames);
        }

        if (trailer != null) {
            out.append(trailer).append(LINE_END);
        }

        return out.append(BUDGET_EXHAUSTED).append(LINE_END).toString();
    }

    private String exhaustedPrefix() {
        return (BUDGET_EXHAUSTED + LINE_END).substring(0, characterBudget);
    }

    @Nullable
    private String renderNatural(Chain chain) {
        int remaining = remainingAfterHeaders(chain.levels, characterBudget, true);
        if (remaining < 0) {
            return null;
        }

        if (chain.trailer != null) {
            if (lineCost(chain.trailer) > remaining) {
                return null;
            }

            remaining -= lineCost(chain.trailer);
        }

        StringBuilder out = new StringBuilder();
        for (Level level : chain.levels) {
            String frames = level.renderFrames(remaining, true);
            if (frames == null) {
                return null;
            }

            level.appendHeader(out);
            out.append(frames);
            remaining -= frames.length();
        }


        if (chain.trailer != null) {
            out.append(chain.trailer).append(LINE_END);
        }
        return out.toString();
    }

    private Chain collect(Throwable root, @Nullable String rootMessage) {
        List<Level> levels = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(root);

        Throwable current = root;
        while (true) {
            String message = levels.isEmpty() ? rootMessage : messageSanitizer.apply(current);
            Level level = new Level(current, !levels.isEmpty(), message);
            levels.add(level);

            Throwable cause = current.getCause();
            if (cause == null) {
                return new Chain(levels, null);
            }

            if (levels.size() > MAX_CAUSED_BY_LEVELS) {
                return new Chain(levels, FURTHER_CAUSES_OMITTED);
            }

            if (!seen.add(cause)) {
                String trailer = CAUSED_BY + "[CIRCULAR REFERENCE: " + cause.getClass().getName() + "]";
                return new Chain(levels, trailer);
            }

            current = cause;
        }
    }

    private static int fitHeaders(List<Level> levels, int bodyBudget) {
        int remaining = remainingAfterHeaders(levels, bodyBudget, false);
        while ((levels.size() > 1) && (remaining < 0)) {
            levels.removeFirst();
            remaining = remainingAfterHeaders(levels, bodyBudget, false);
        }

        if (remaining < 0) {
            Level level = levels.getFirst();
            int end = bodyBudget - 1;
            if ((end > 0) && Character.isHighSurrogate(level.headerWithoutMessage.charAt(end - 1))
                    && Character.isLowSurrogate(level.headerWithoutMessage.charAt(end))) {
                end--;
            }

            level.selectedHeader = level.headerWithoutMessage.substring(0, end);
            return bodyBudget - lineCost(level.selectedHeader);
        }

        for (int index = levels.size() - 1; index >= 0; index--) {
            Level level = levels.get(index);
            String levelMessage = level.message();
            if ((levelMessage != null) && (remaining >= 2) && (levelMessage.length() <= remaining - 2)) {
                level.selectedHeader = level.headerWithoutMessage + ": " + levelMessage;
                remaining -= levelMessage.length() + 2;
            }
        }

        return remaining;
    }

    private static int remainingAfterHeaders(List<Level> levels, int budget, boolean includeMessages) {
        for (Level level : levels) {
            if (level.headerWithoutMessage.length() >= budget) {
                return -1;
            }

            budget -= lineCost(level.headerWithoutMessage);
            String levelMessage = level.message();
            if (includeMessages && (levelMessage != null)) {
                if ((budget < 2) || (levelMessage.length() > budget - 2)) {
                    return -1;
                }

                budget -= levelMessage.length() + 2;
            }
        }

        return budget;
    }

    private static int lineCost(String line) {
        return line.length() + 1;
    }

    static String framesOmitted(int omitted) {
        return "... (" + omitted + " frames omitted)";
    }

    private record Chain(List<Level> levels, @Nullable String trailer) {
    }

    private final class Level {

        private final Throwable throwable;
        private final String headerWithoutMessage;
        @Nullable
        private final String message;
        @Nullable
        private StackTraceElement[] frames;
        private List<String> naturalFrames = List.of();
        private String selectedHeader;
        private String selectedFrames = "";

        Level(Throwable throwable, boolean causedBy, @Nullable String message) {
            this.throwable = throwable;
            this.headerWithoutMessage = (causedBy ? CAUSED_BY : "") + throwable.getClass().getName();
            this.message = message;
            this.selectedHeader = headerWithoutMessage;
        }

        @Nullable
        String message() {
            return message;
        }

        void appendHeader(StringBuilder out) {
            out.append(headerWithoutMessage);
            if (message != null) {
                out.append(": ").append(message);
            }
            out.append(LINE_END);
        }

        @Nullable
        String renderFrames(int budget, boolean natural) {
            if (!natural && (budget <= FRAME_PREFIX.length())) {
                return "";
            }

            if (frames == null) {
                frames = throwable.getStackTrace();
                if (frames == null) {
                    frames = NO_FRAMES;
                }
            }

            StackTraceElement[] resolvedFrames = Objects.requireNonNull(frames, "frames");
            List<String> selected = new ArrayList<>();
            int cost = 0;
            int frameLimit = Math.min(MAX_FRAMES, resolvedFrames.length);
            while (selected.size() < frameLimit) {
                String line = frameLine(resolvedFrames, selected.size(), budget - cost);
                if (line == null) {
                    break;
                }

                selected.add(line);
                cost += lineCost(line);
            }

            if (natural) {
                naturalFrames = selected;
                if (selected.size() < frameLimit) {
                    return null;
                }
            }

            String marker = null;
            if (selected.size() < resolvedFrames.length) {
                marker = framesOmitted(resolvedFrames.length - selected.size());
                while (lineCost(marker) > budget - cost) {
                    if (natural) {
                        return null;
                    }

                    if (selected.isEmpty()) {
                        return "";
                    }

                    cost -= lineCost(selected.removeLast());
                    marker = framesOmitted(resolvedFrames.length - selected.size());
                }
            }

            StringBuilder out = new StringBuilder();
            for (String line : selected) {
                out.append(line).append(LINE_END);
            }

            if (marker != null) {
                out.append(marker).append(LINE_END);
            }

            return out.toString();
        }

        @Nullable
        private String frameLine(@Nonnull StackTraceElement[] resolvedFrames, int index, int remaining) {
            if (remaining <= FRAME_PREFIX.length()) {
                return null;
            }

            String line;
            if (index < naturalFrames.size()) {
                line = naturalFrames.get(index);
            } else {
                StackTraceElement frame = resolvedFrames[index];
                if ((frame != null) && !frameComponentsFit(frame)) {
                    return null;
                }

                line = FRAME_PREFIX + frame;
            }

            return line.length() < remaining ? line : null;
        }
    }

    private boolean frameComponentsFit(StackTraceElement frame) {
        long length = (long) length(frame.getClassLoaderName()) + length(frame.getModuleName())
                + length(frame.getModuleVersion()) + length(frame.getClassName())
                + length(frame.getMethodName()) + length(frame.getFileName());
        return length <= characterBudget;
    }

    private static int length(@Nullable String value) {
        return (value == null) ? 0 : value.length();
    }
}
