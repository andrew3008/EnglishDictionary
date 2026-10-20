package space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class RuleCircuitBreakerPrivacyProbe {

    public static void main(String[] args) {
        String secret = requiredSecret(args);
        new RuleCircuitBreaker("privacy-canary", 1, 0, 0).recordFailure(secretFailure(secret));
    }

    private static RuntimeException secretFailure(String secret) {
        RuntimeException root = new RuntimeException(secret, new SecretToStringFailure(secret));
        root.addSuppressed(new IllegalStateException(secret));
        return root;
    }

    private static String requiredSecret(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Exactly one non-blank secret marker is required");
        }

        return args[0];
    }

    private static final class SecretToStringFailure extends RuntimeException {

        private final String rendered;

        private SecretToStringFailure(String rendered) {
            super(rendered);
            this.rendered = rendered;
        }

        @Override
        public String toString() {
            return rendered;
        }
    }
}
