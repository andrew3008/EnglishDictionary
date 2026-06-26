package space.br1440.platform.datasource.postgres.api.properties;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record ReadReplicaProperties(
        @DefaultValue("SECONDARY_PREFERRED") ReadMode mode
) {

    public ReadReplicaProperties {
        mode = (mode == null) ? ReadMode.SECONDARY_PREFERRED : mode;
    }
}
