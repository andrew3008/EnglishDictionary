package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

record ChildProcessResult(boolean finished, Integer exitCode, String stdout, String stderr) {

    void assertCompletedSuccessfully() {
        assertThat(finished).as("Controlled agent child JVM must terminate").isTrue();
        assertThat(exitCode)
                .as("Controlled agent child JVM failed; stdout=%s, stderr=%s", stdout, stderr)
                .isZero();
    }
}
