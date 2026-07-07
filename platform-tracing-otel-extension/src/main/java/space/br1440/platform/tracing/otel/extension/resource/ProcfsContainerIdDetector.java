package space.br1440.platform.tracing.otel.extension.resource;

import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Локальный резолв {@code container.id} из {@code /proc/self/cgroup} (v1).
 * <p>
 * Используется только если explicit config/env не заданы и OTel Agent Container detector
 * отключён. Pod UID намеренно не используется.
 */
public final class ProcfsContainerIdDetector {

    private static final Path DEFAULT_CGROUP_PATH = Path.of("/proc/self/cgroup");

    private static final Pattern DOCKER_FULL =
            Pattern.compile("/docker/([0-9a-f]{64})(?:/|$)");
    private static final Pattern CONTAINERD =
            Pattern.compile("cri-containerd-([0-9a-f]{64})\\.scope");
    private static final Pattern DOCKER_SHORT =
            Pattern.compile("/docker/([0-9a-f]{12})(?:[^0-9a-f]|$)");

    private final Function<Path, Optional<String>> cgroupReader;

    public ProcfsContainerIdDetector() {
        this(ProcfsContainerIdDetector::readCgroupFile);
    }

    ProcfsContainerIdDetector(Function<Path, Optional<String>> cgroupReader) {
        this.cgroupReader = cgroupReader;
    }

    /**
     * Читает cgroup-файл и извлекает runtime container ID, если распознан.
     */
    public Optional<String> detect() {
        return cgroupReader.apply(DEFAULT_CGROUP_PATH).flatMap(ProcfsContainerIdDetector::parse);
    }

    /**
     * Парсит содержимое {@code /proc/self/cgroup} без обращения к файловой системе (для тестов).
     */
    static Optional<String> parse(String cgroupContent) {
        if (Strings.isBlank(cgroupContent)) {
            return Optional.empty();
        }
        for (String line : cgroupContent.split("\n")) {
            Optional<String> id = parseLine(line);
            if (id.isPresent()) {
                return id;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> parseLine(String line) {
        int lastColon = line.lastIndexOf(':');
        if (lastColon < 0 || lastColon >= line.length() - 1) {
            return Optional.empty();
        }
        String path = line.substring(lastColon + 1).trim();
        return firstMatch(DOCKER_FULL, path)
                .or(() -> firstMatch(CONTAINERD, path))
                .or(() -> firstMatch(DOCKER_SHORT, path));
    }

    private static Optional<String> firstMatch(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> readCgroupFile(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | SecurityException ignored) {
            return Optional.empty();
        }
    }
}
