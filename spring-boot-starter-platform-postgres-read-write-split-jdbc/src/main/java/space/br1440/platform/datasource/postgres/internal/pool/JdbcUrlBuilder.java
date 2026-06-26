package space.br1440.platform.datasource.postgres.internal.pool;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@UtilityClass
final class JdbcUrlBuilder {

    private static final String JDBC_PREFIX = "jdbc:postgresql://";
    private static final String LOAD_BALANCE_HOSTS_KEY = "loadBalanceHosts";
    private static final String CURRENT_SCHEMA_KEY = "currentSchema";

    static String writeUrl(PgClusterProperties properties) {
        return buildUrl(
                properties.nodes(),
                properties.database(),
                "primary",
                false,
                properties.jdbc().properties()
        );
    }

    static String readUrl(PgClusterProperties properties) {
        String targetServerType;
        boolean loadBalanceHosts;

        ReadMode mode = properties.read().mode();
        switch (mode) {
            case SECONDARY_PREFERRED -> {
                targetServerType = "preferSecondary";
                loadBalanceHosts = false;
            }

            case STRICT_SECONDARY -> {
                targetServerType = "secondary";
                loadBalanceHosts = false;
            }

            case ANY_NODE_LOAD_BALANCED -> {
                targetServerType = "any";
                loadBalanceHosts = true;
            }

            default -> throw new IllegalStateException("Unsupported read mode: " + mode);
        }

        return buildUrl(
                properties.nodes(),
                properties.database(),
                targetServerType,
                loadBalanceHosts,
                properties.jdbc().properties()
        );
    }

    private static String buildUrl(List<PgNode> nodes,
                                   String database,
                                   String targetServerType,
                                   boolean loadBalanceHosts,
                                   Map<String, String> driverProperties) {
        if (CollectionUtils.isEmpty(nodes)) {
            throw new IllegalArgumentException("Cluster nodes must not be empty");
        }

        if (StringUtils.isBlank(database)) {
            throw new IllegalArgumentException("Database name must not be blank");
        }

        if (StringUtils.isBlank(targetServerType)) {
            throw new IllegalArgumentException("targetServerType must not be blank");
        }

        Map<String, String> props = (driverProperties == null)
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(driverProperties);

        String currentSchema = props.remove(CURRENT_SCHEMA_KEY);

        StringBuilder url = new StringBuilder(JDBC_PREFIX)
                .append(serializeNodes(nodes))
                .append("/")
                .append(database)
                .append("?targetServerType=").append(targetServerType);

        if (loadBalanceHosts) {
            url.append("&").append(LOAD_BALANCE_HOSTS_KEY).append("=true");
        }

        if (StringUtils.isNotBlank(currentSchema)) {
            url.append("&").append(CURRENT_SCHEMA_KEY).append("=").append(encode(currentSchema.strip()));
        }

        props.forEach((key, value) ->
                url.append("&").append(encode(key)).append("=").append(encode(value)));

        return url.toString();
    }

    private static String serializeNodes(List<PgNode> nodes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                builder.append(",");
            }

            builder.append(nodes.get(i).toHostPort());
        }

        return builder.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
