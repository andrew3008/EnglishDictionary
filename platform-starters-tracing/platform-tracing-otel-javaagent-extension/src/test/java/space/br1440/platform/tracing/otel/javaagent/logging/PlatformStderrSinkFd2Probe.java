package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.OutputStream;
import java.io.PrintStream;

import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformStderrSinkFd2Probe {

    public static void main(String[] args) {
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));

        PlatformAgentLogger.get(PlatformStderrSinkFd2Probe.class)
                .log(BootstrapLogEvent.EXTENSION_INSTALLED);
    }
}
