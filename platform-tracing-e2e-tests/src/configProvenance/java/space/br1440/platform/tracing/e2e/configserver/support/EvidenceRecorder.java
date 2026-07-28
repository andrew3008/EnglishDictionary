package space.br1440.platform.tracing.e2e.configserver.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class EvidenceRecorder {

    private static final Object MONITOR = new Object();

    private EvidenceRecorder() {
    }

    public static void record(
            String scenario,
            ProvenanceInspector.KeyEvidence evidence,
            ProvenanceInspector.MarkerEvidence marker,
            List<String> tree,
            String details
    ) {
        StringBuilder text = new StringBuilder()
                .append(System.lineSeparator())
                .append("=== ").append(scenario).append(" ===").append(System.lineSeparator())
                .append("effective=").append(evidence).append(System.lineSeparator())
                .append("marker=").append(marker).append(System.lineSeparator())
                .append("details=").append(details).append(System.lineSeparator());
        for (String line : tree) {
            text.append("tree=").append(line).append(System.lineSeparator());
        }
        append(text.toString());
    }

    public static void record(String scenario, String details) {
        append(System.lineSeparator() + "=== " + scenario + " ===" + System.lineSeparator()
                + details + System.lineSeparator());
    }

    private static void append(String text) {
        String configured = System.getProperty("config.provenance.evidence.file");
        if (configured == null || configured.isBlank()) {
            return;
        }
        synchronized (MONITOR) {
            Path path = Path.of(configured);
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(
                        path,
                        text,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                throw new IllegalStateException("Не удалось записать evidence Step 0B", exception);
            }
        }
    }
}
