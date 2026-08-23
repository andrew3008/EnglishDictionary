package space.br1440.platform.tracing.otel.javaagent.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import space.br1440.platform.common.util.StringUtils;

/**
 * Локальный резолвер {@code container.id} из {@code /proc/self/cgroup}.
 *
 * <p>Используется только тогда, когда ни явная конфигурация, ни переменные окружения не
 * предоставляют container ID и детектор контейнера OpenTelemetry Agent отключён.
 * Pod UID намеренно не используется.
 */
public final class ProcfsContainerIdDetector {

    private static final Path DEFAULT_CGROUP_PATH = Path.of("/proc/self/cgroup");

    private static final List<Pattern> CONTAINER_ID_PATTERNS = List.of(
            Pattern.compile("/docker/([0-9a-f]{64})(?:/|$)"),
            Pattern.compile("cri-containerd-([0-9a-f]{64})\\.scope"),
            Pattern.compile("/docker/([0-9a-f]{12})(?:[^0-9a-f]|$)")
    );

    private final CgroupFileReader cgroupFileReader;

    public ProcfsContainerIdDetector() {
        this(ProcfsContainerIdDetector::readCgroupFile);
    }

    ProcfsContainerIdDetector(CgroupFileReader cgroupFileReader) {
        this.cgroupFileReader = Objects.requireNonNull(cgroupFileReader, "cgroupFileReader");
    }

    public Optional<String> detect() {
        return cgroupFileReader.read(DEFAULT_CGROUP_PATH)
                .flatMap(ProcfsContainerIdDetector::parse);
    }

    static Optional<String> parse(String cgroupContent) {
        if (StringUtils.isNullOrBlank(cgroupContent)) {
            return Optional.empty();
        }

        for (String line : cgroupContent.split("\\R")) {
            Optional<String> containerId = parseLine(line);
            if (containerId.isPresent()) {
                return containerId;
            }
        }

        return Optional.empty();
    }

    private static Optional<String> parseLine(String line) {
        int pathSeparatorIndex = line.lastIndexOf(':');
        if (pathSeparatorIndex < 0 || pathSeparatorIndex == line.length() - 1) {
            return Optional.empty();
        }

        String cgroupPath = line.substring(pathSeparatorIndex + 1).strip();
        return findContainerId(cgroupPath);
    }

    private static Optional<String> findContainerId(String cgroupPath) {
        for (Pattern pattern : CONTAINER_ID_PATTERNS) {
            Matcher matcher = pattern.matcher(cgroupPath);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }

        return Optional.empty();
    }

    private static Optional<String> readCgroupFile(Path path) {
        try {
            String fileContent = Files.readString(path, StandardCharsets.UTF_8);
            return Optional.of(fileContent);
        } catch (IOException | SecurityException exception) {
            return Optional.empty();
        }
    }

    @FunctionalInterface
    interface CgroupFileReader {

        Optional<String> read(Path path);
    }
}
