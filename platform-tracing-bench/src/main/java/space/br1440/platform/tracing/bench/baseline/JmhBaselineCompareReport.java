package space.br1440.platform.tracing.bench.baseline;

import java.util.ArrayList;
import java.util.List;

public final class JmhBaselineCompareReport {

    private final String profileId;
    private final int baselineEntries;
    private final int currentEntries;
    private final int matched;
    private final int newEntries;
    private final int hardWarnings;
    private final int hardFailures;
    private final int diagnosticWarnings;
    private final int diagnosticFailures;
    private final List<String> lines;
    private final List<String> hardWarningMessages;
    private final List<String> hardFailureMessages;
    private final List<String> diagnosticWarningMessages;
    private final List<String> diagnosticFailureMessages;

    JmhBaselineCompareReport(
            String profileId,
            int baselineEntries,
            int currentEntries,
            int matched,
            int newEntries,
            int hardWarnings,
            int hardFailures,
            int diagnosticWarnings,
            int diagnosticFailures,
            List<String> lines,
            List<String> hardWarningMessages,
            List<String> hardFailureMessages,
            List<String> diagnosticWarningMessages,
            List<String> diagnosticFailureMessages) {
        this.profileId = profileId;
        this.baselineEntries = baselineEntries;
        this.currentEntries = currentEntries;
        this.matched = matched;
        this.newEntries = newEntries;
        this.hardWarnings = hardWarnings;
        this.hardFailures = hardFailures;
        this.diagnosticWarnings = diagnosticWarnings;
        this.diagnosticFailures = diagnosticFailures;
        this.lines = List.copyOf(lines);
        this.hardWarningMessages = List.copyOf(hardWarningMessages);
        this.hardFailureMessages = List.copyOf(hardFailureMessages);
        this.diagnosticWarningMessages = List.copyOf(diagnosticWarningMessages);
        this.diagnosticFailureMessages = List.copyOf(diagnosticFailureMessages);
    }

    public String profileId() {
        return profileId;
    }

    public int baselineEntries() {
        return baselineEntries;
    }

    public int currentEntries() {
        return currentEntries;
    }

    public int matched() {
        return matched;
    }

    public int newEntries() {
        return newEntries;
    }

    public int hardWarnings() {
        return hardWarnings;
    }

    public int hardFailures() {
        return hardFailures;
    }

    public int diagnosticWarnings() {
        return diagnosticWarnings;
    }

    public int diagnosticFailures() {
        return diagnosticFailures;
    }

    /** Total WARN count across hard and diagnostic gates (reporting only). */
    public int warnings() {
        return hardWarnings + diagnosticWarnings;
    }

    /** Total FAIL count across hard and diagnostic gates (reporting only). */
    public int failures() {
        return hardFailures + diagnosticFailures;
    }

    public int skipped() {
        return newEntries;
    }

    public List<String> lines() {
        return lines;
    }

    public List<String> hardWarningMessages() {
        return hardWarningMessages;
    }

    public List<String> hardFailureMessages() {
        return hardFailureMessages;
    }

    public List<String> diagnosticWarningMessages() {
        return diagnosticWarningMessages;
    }

    public List<String> diagnosticFailureMessages() {
        return diagnosticFailureMessages;
    }

    /** @deprecated use {@link #hardWarningMessages()} */
    @Deprecated
    public List<String> warningMessages() {
        return hardWarningMessages;
    }

    /** @deprecated use {@link #hardFailureMessages()} */
    @Deprecated
    public List<String> failureMessages() {
        return hardFailureMessages;
    }

    public boolean hasHardFailures() {
        return hardFailures > 0;
    }

    public boolean hasDiagnosticFailures() {
        return diagnosticFailures > 0;
    }

    public boolean hasFailures() {
        return hasHardFailures();
    }

    public boolean hasBlockingFailures(boolean failOnSampleRegression) {
        if (hasHardFailures()) {
            return true;
        }
        return failOnSampleRegression && hasDiagnosticFailures();
    }

    public boolean gateUnreliable() {
        return currentEntries > 0 && baselineEntries > 0 && matched == 0;
    }

    static Builder builder(String profileId) {
        return new Builder(profileId);
    }

    static final class Builder {
        private final String profileId;
        private int baselineEntries;
        private int currentEntries;
        private int matched;
        private int newEntries;
        private int hardWarnings;
        private int hardFailures;
        private int diagnosticWarnings;
        private int diagnosticFailures;
        private final List<String> lines = new ArrayList<>();
        private final List<String> hardWarningMessages = new ArrayList<>();
        private final List<String> hardFailureMessages = new ArrayList<>();
        private final List<String> diagnosticWarningMessages = new ArrayList<>();
        private final List<String> diagnosticFailureMessages = new ArrayList<>();

        private Builder(String profileId) {
            this.profileId = profileId;
        }

        Builder baselineEntries(int value) {
            this.baselineEntries = value;
            return this;
        }

        Builder currentEntries(int value) {
            this.currentEntries = value;
            return this;
        }

        Builder matched(int value) {
            this.matched = value;
            return this;
        }

        Builder newEntries(int value) {
            this.newEntries = value;
            return this;
        }

        Builder hardWarnings(int value) {
            this.hardWarnings = value;
            return this;
        }

        Builder hardFailures(int value) {
            this.hardFailures = value;
            return this;
        }

        Builder diagnosticWarnings(int value) {
            this.diagnosticWarnings = value;
            return this;
        }

        Builder diagnosticFailures(int value) {
            this.diagnosticFailures = value;
            return this;
        }

        Builder addLine(String line) {
            lines.add(line);
            return this;
        }

        Builder addHardWarning(String message) {
            hardWarningMessages.add(message);
            return this;
        }

        Builder addHardFailure(String message) {
            hardFailureMessages.add(message);
            return this;
        }

        Builder addDiagnosticWarning(String message) {
            diagnosticWarningMessages.add(message);
            return this;
        }

        Builder addDiagnosticFailure(String message) {
            diagnosticFailureMessages.add(message);
            return this;
        }

        JmhBaselineCompareReport build() {
            return new JmhBaselineCompareReport(
                    profileId,
                    baselineEntries,
                    currentEntries,
                    matched,
                    newEntries,
                    hardWarnings,
                    hardFailures,
                    diagnosticWarnings,
                    diagnosticFailures,
                    lines,
                    hardWarningMessages,
                    hardFailureMessages,
                    diagnosticWarningMessages,
                    diagnosticFailureMessages);
        }
    }
}
