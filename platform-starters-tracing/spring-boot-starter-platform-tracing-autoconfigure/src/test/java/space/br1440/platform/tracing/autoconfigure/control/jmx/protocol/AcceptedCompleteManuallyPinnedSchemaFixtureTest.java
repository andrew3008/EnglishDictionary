package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;

/**
 * Закрепляет имена и OpenType снимка принятой политики независимо от генератора.
 */
class AcceptedCompleteManuallyPinnedSchemaFixtureTest {

    @Test
    void pinnedAcceptedSnapshotTypeEqualsGeneratedContract() throws OpenDataException {
        assertThat(pinnedAcceptedSnapshotType()).isEqualTo(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT);
    }

    private static CompositeType pinnedAcceptedSnapshotType() throws OpenDataException {
        CompositeType routeRow = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING}
        );

        TabularType routes = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                routeRow,
                new String[]{"routePrefix"}
        );

        String[] names = {
                "acceptedSequence",
                "agentInstanceId",
                "sourceAuthority",
                "samplingEnabled",
                "samplingRatio",
                "samplingRouteRatios",
                "validationLevel",
                "exporterEnabled",
                "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };

        return new CompositeType(
                "AcceptedCompleteSnapshot/v1",
                "AcceptedCompleteSnapshot/v1",
                names,
                names,
                new OpenType<?>[]{
                        SimpleType.LONG,
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.BOOLEAN,
                        SimpleType.DOUBLE,
                        routes,
                        SimpleType.STRING,
                        SimpleType.BOOLEAN,
                        SimpleType.BOOLEAN,
                        SimpleType.STRING
                }
        );
    }
}
