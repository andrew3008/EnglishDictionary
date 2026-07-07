package space.br1440.platform.tracing.api.control.protocol.schema;

public enum TracingControlProtocolTypes {

    STRING,
    BOOLEAN,
    INTEGER,
    LONG,
    DOUBLE,
    STRING_ARRAY,
    ROUTE_RATIOS_MAP;

    public boolean ratioBounded() {
        return this == DOUBLE;
    }
}
