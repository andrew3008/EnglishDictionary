package space.br1440.platform.datasource.postgres.api.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.api.properties.ReadReplicaProperties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReadReplicaProperties / ReadMode — честная семантика read-стратегии")
class ReadReplicaPropertiesTest {

    @Test
    void mode_defaultsToSecondaryPreferred_whenNull() {
        assertThat(new ReadReplicaProperties(null).mode())
                .as("Дефолт read-режима — SECONDARY_PREFERRED (failover-safe чтение)")
                .isEqualTo(ReadMode.SECONDARY_PREFERRED);
    }

    @Test
    void mode_isPreserved_whenExplicit() {
        assertThat(new ReadReplicaProperties(ReadMode.STRICT_SECONDARY).mode())
                .isEqualTo(ReadMode.STRICT_SECONDARY);
    }

    @Test
    void readMode_containsHonestValues() {
        assertThat(ReadMode.values())
                .containsExactly(
                        ReadMode.SECONDARY_PREFERRED,
                        ReadMode.STRICT_SECONDARY,
                        ReadMode.ANY_NODE_LOAD_BALANCED);
    }
}
