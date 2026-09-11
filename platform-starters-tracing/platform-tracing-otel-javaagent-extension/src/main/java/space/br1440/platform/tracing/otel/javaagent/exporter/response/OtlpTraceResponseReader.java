package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import jakarta.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
final class OtlpTraceResponseReader {

    static void record(ExportResponseObservation observation, @Nullable byte[] responseMessage) {
        if (responseMessage == null) {
            observation.recordNotDetermined();
            return;
        }

        long rejected;
        try {
            rejected = ExportTraceServiceResponse.parseFrom(responseMessage)
                    .getPartialSuccess()
                    .getRejectedSpans();
        } catch (InvalidProtocolBufferException exception) {
            observation.recordNotDetermined();
            return;
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            observation.recordNotDetermined();
            return;
        }

        if (rejected == 0L) {
            observation.recordAllAccepted();
            return;
        }

        observation.recordRejected(rejected);
    }
}
