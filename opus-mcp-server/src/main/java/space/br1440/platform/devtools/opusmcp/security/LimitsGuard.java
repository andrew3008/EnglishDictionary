package space.br1440.platform.devtools.opusmcp.security;

public record LimitsGuard(int maxContextChars, int maxConstraintsChars, int maxOutputChars) {

    public record TruncationResult(String value, boolean truncated) {
    }

    public OptionalLimit checkContextSize(String context) {
        return checkSize(context, maxContextChars, "context");
    }

    public OptionalLimit checkConstraintsSize(String constraints) {
        return checkSize(constraints, maxConstraintsChars, "constraints");
    }

    public TruncationResult truncateOutput(String output) {
        if (output == null) {
            return new TruncationResult("", false);
        }
        if (output.length() <= maxOutputChars) {
            return new TruncationResult(output, false);
        }
        return new TruncationResult(output.substring(0, maxOutputChars), true);
    }

    private OptionalLimit checkSize(String value, int max, String fieldName) {
        if (value == null || value.isEmpty()) {
            return OptionalLimit.ok();
        }
        if (value.length() > max) {
            return OptionalLimit.exceeded(fieldName + " exceeds maximum size of " + max + " characters");
        }
        return OptionalLimit.ok();
    }

    public record OptionalLimit(boolean exceeded, String message) {
        public static OptionalLimit ok() {
            return new OptionalLimit(false, null);
        }

        public static OptionalLimit exceeded(String message) {
            return new OptionalLimit(true, message);
        }
    }
}
