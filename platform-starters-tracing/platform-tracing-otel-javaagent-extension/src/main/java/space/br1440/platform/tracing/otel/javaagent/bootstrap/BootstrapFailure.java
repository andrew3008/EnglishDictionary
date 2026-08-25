package space.br1440.platform.tracing.otel.javaagent.bootstrap;

record BootstrapFailure(String code, String phase, Throwable cause) {
}
