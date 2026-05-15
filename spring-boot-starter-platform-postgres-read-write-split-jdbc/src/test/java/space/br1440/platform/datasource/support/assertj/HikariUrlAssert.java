package space.br1440.platform.datasource.support.assertj;

import org.assertj.core.api.AbstractAssert;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fluent AssertJ для разбора JDBC URL формата pgJDBC multi-host:
 * {@code jdbc:postgresql://host1:p1,host2:p2/db?key=value&key2=value2}.
 */
public class HikariUrlAssert extends AbstractAssert<HikariUrlAssert, String> {

    private final String hostList;
    private final String database;
    private final Map<String, String> params;

    public HikariUrlAssert(String url) {
        super(url, HikariUrlAssert.class);
        if (url == null) {
            this.hostList = null;
            this.database = null;
            this.params = Map.of();
            return;
        }
        if (!url.startsWith("jdbc:postgresql://")) {
            failWithMessage("Expected URL to start with 'jdbc:postgresql://' but was '%s'", url);
            this.hostList = null;
            this.database = null;
            this.params = Map.of();
            return;
        }
        String body = url.substring("jdbc:postgresql://".length());
        int qmark = body.indexOf('?');
        String hostsAndDb = qmark >= 0 ? body.substring(0, qmark) : body;
        String query = qmark >= 0 ? body.substring(qmark + 1) : "";

        int slash = hostsAndDb.indexOf('/');
        this.hostList = slash >= 0 ? hostsAndDb.substring(0, slash) : hostsAndDb;
        this.database = slash >= 0 ? hostsAndDb.substring(slash + 1) : null;
        this.params = parseParams(query);
    }

    private Map<String, String> parseParams(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query.isEmpty()) {
            return result;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq >= 0) {
                String k = decode(part.substring(0, eq));
                String v = decode(part.substring(eq + 1));
                result.put(k, v);
            } else {
                result.put(decode(part), "");
            }
        }
        return result;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public HikariUrlAssert hasDatabase(String expected) {
        isNotNull();
        if (!expected.equals(database)) {
            failWithMessage("Expected database <%s> but was <%s> (url: %s)", expected, database, actual);
        }
        return this;
    }

    public HikariUrlAssert hasHost(String expected) {
        isNotNull();
        if (hostList == null || !hostList.contains(expected)) {
            failWithMessage("Expected hostList to contain <%s> but was <%s>", expected, hostList);
        }
        return this;
    }

    public HikariUrlAssert hasHostCount(int expected) {
        isNotNull();
        int actualCount = hostList == null || hostList.isEmpty() ? 0 : hostList.split(",").length;
        if (actualCount != expected) {
            failWithMessage("Expected <%d> hosts but found <%d> (url: %s)", expected, actualCount, actual);
        }
        return this;
    }

    public HikariUrlAssert hasParam(String key, String value) {
        isNotNull();
        if (!params.containsKey(key)) {
            failWithMessage("Expected URL param <%s>=<%s> but param missing (url: %s)", key, value, actual);
        } else if (!value.equals(params.get(key))) {
            failWithMessage(
                    "Expected URL param <%s>=<%s> but was <%s> (url: %s)",
                    key, value, params.get(key), actual);
        }
        return this;
    }

    public HikariUrlAssert hasParam(String key) {
        isNotNull();
        if (!params.containsKey(key)) {
            failWithMessage("Expected URL to have param <%s> but it was missing (url: %s)", key, actual);
        }
        return this;
    }

    public HikariUrlAssert hasNoParam(String key) {
        isNotNull();
        if (params.containsKey(key)) {
            failWithMessage(
                    "Expected URL to have NO param <%s> but it was present with value <%s> (url: %s)",
                    key, params.get(key), actual);
        }
        return this;
    }

    public HikariUrlAssert hasTargetServerType(String expected) {
        return hasParam("targetServerType", expected);
    }

    public HikariUrlAssert hasParamCount(int expected) {
        isNotNull();
        if (params.size() != expected) {
            failWithMessage(
                    "Expected <%d> URL params but found <%d>: %s (url: %s)",
                    expected, params.size(), params, actual);
        }
        return this;
    }

    public Map<String, String> params() {
        return Map.copyOf(params);
    }

    public String hostList() {
        return hostList;
    }
}
