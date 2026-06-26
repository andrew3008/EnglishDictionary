package space.br1440.platform.datasource.postgres.internal.bootstrap;

import lombok.experimental.UtilityClass;
import org.springframework.util.StringUtils;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;

import java.util.ArrayList;
import java.util.List;

@UtilityClass
public final class PgNodesParser {

    static final String MSG_MALFORMED = "Malformed PG_CLUSTER_NODES: ";

    static final String ACTION_MALFORMED = """
            Expected a non-empty CSV list of host:port pairs, e.g.:
              PG_CLUSTER_NODES=10.116.37.137:5432,10.116.37.138:5432
            IPv6 hosts must be bracketed: [::1]:5432""".stripIndent();

    public static List<PgNode> parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw fail("value is missing or blank");
        }

        List<PgNode> nodes = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }

            nodes.add(parseSingle(trimmed));
        }

        if (nodes.isEmpty()) {
            throw fail("no host:port entries found in '" + raw + "'");
        }

        return nodes;
    }

    private static PgNode parseSingle(String hostPort) {
        String host;
        String portRaw;

        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0 || close + 1 >= hostPort.length() || hostPort.charAt(close + 1) != ':') {
                throw fail("invalid IPv6 host:port '" + hostPort + "'");
            }

            host = hostPort.substring(1, close);
            portRaw = hostPort.substring(close + 2);
        } else {
            int sep = hostPort.lastIndexOf(':');
            if (sep <= 0 || sep == hostPort.length() - 1) {
                throw fail("expected host:port but got '" + hostPort + "'");
            }

            host = hostPort.substring(0, sep);
            portRaw = hostPort.substring(sep + 1);
        }

        int port;
        try {
            port = Integer.parseInt(portRaw);
        } catch (NumberFormatException ex) {
            throw fail("port '" + portRaw + "' in '" + hostPort + "' is not a number");
        }

        try {
            return new PgNode(host, port);
        } catch (IllegalArgumentException ex) {
            throw fail(ex.getMessage());
        }
    }

    private static PostgresClusterConfigurationException fail(String detail) {
        return new PostgresClusterConfigurationException(MSG_MALFORMED + detail, ACTION_MALFORMED);
    }
}
