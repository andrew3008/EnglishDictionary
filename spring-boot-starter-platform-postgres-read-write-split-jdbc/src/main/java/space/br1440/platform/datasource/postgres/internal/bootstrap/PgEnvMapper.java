package space.br1440.platform.datasource.postgres.internal.bootstrap;

import space.br1440.platform.datasource.postgres.internal.bootstrap.PgEnvReader.RawPgEnv;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PgEnvMapper {

    public Map<String, Object> toCanonical(RawPgEnv env) {
        Map<String, Object> canonical = new LinkedHashMap<>();

        if (env.routingMode() != null) {
            canonical.put(PgClusterProperties.PREFIX + ".datasource-routing-mode", env.routingMode());
        }

        if (env.nodes() != null) {
            putNodes(canonical, PgNodesParser.parse(env.nodes()));
        }

        if (env.database() != null) {
            canonical.put(PgClusterProperties.PREFIX + ".database", env.database());
        }

        if (env.username() != null) {
            canonical.put(PgClusterProperties.PREFIX + ".credentials.username", env.username());
        }

        if (env.password() != null) {
            canonical.put(PgClusterProperties.PREFIX + ".credentials.password", env.password());
        }

        if (env.connectionProperties() != null) {
            Map<String, String> parsed = PgConnectionPropertiesParser.parse(env.connectionProperties());
            parsed.forEach((k, v) -> canonical.put(PgClusterProperties.PREFIX + ".jdbc.properties." + k, v));

            if (env.schema() != null && parsed.containsKey("currentSchema")) {
                canonical.put(PgClusterProperties.PREFIX + ".__pgSchemaConflict", "true");
            }
        }

        if (env.schema() != null) {
            canonical.put(PgClusterProperties.PREFIX + ".jdbc.properties.currentSchema", env.schema());
        }

        return canonical;
    }

    private static void putNodes(Map<String, Object> canonical, List<PgNode> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            canonical.put(PgClusterProperties.PREFIX + ".nodes[" + i + "].host", nodes.get(i).host());
            canonical.put(PgClusterProperties.PREFIX + ".nodes[" + i + "].port", nodes.get(i).port());
        }
    }
}
