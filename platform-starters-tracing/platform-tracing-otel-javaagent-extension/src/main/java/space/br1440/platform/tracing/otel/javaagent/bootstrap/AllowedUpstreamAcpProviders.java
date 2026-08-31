package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class AllowedUpstreamAcpProviders {

    private static final String RESOURCE_PATH = "/META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final int MAX_RESOURCE_BYTES = 16 * 1024;
    private static final String EXPECTED_FORMAT_VERSION = "1";
    private static final String FORMAT_VERSION_KEY = "format.version";
    private static final String OTEL_JAVA_AGENT_SHA256_KEY = "otel.javaagent.sha256";
    private static final String PROVIDER_KEY_PREFIX = "provider.";
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern JAVA_BINARY_NAME_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*"
    );

    static final Set<String> NAMES = load();

    private AllowedUpstreamAcpProviders() {
    }

    private static Set<String> load() {
        try (InputStream input = AllowedUpstreamAcpProviders.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("ACP pin resource is missing: " + RESOURCE_PATH);
            }

            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IllegalStateException("ACP pin resource exceeds the size limit: " + RESOURCE_PATH);
            }

            return parse(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ACP pin resource: " + RESOURCE_PATH, e);
        }
    }

    static Set<String> parse(byte[] bytes) {
        String content = decodeUtf8(bytes);
        if (!content.endsWith("\n")) {
            throw invalid("resource must end with LF");
        }

        if (content.indexOf('\r') >= 0) {
            throw invalid("CR characters are not allowed");
        }

        String formatVersion = null;
        String otelJavaAgentSha256 = null;
        Map<Integer, String> providers = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        String[] lines = content.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length - 1; lineIndex++) {
            String line = lines[lineIndex];
            int separatorIndex = line.indexOf('=');
            if (separatorIndex <= 0 || separatorIndex != line.lastIndexOf('=')
                    || separatorIndex == line.length() - 1) {
                throw invalid("line " + (lineIndex + 1) + " must contain one non-empty key=value pair");
            }

            String key = line.substring(0, separatorIndex);
            String value = line.substring(separatorIndex + 1);
            if (!key.equals(key.strip()) || !value.equals(value.strip())) {
                throw invalid("whitespace around keys or values is not allowed on line " + (lineIndex + 1));
            }
            if (!keys.add(key)) {
                throw invalid("duplicate key: " + key);
            }

            if (FORMAT_VERSION_KEY.equals(key)) {
                formatVersion = value;
            } else if (OTEL_JAVA_AGENT_SHA256_KEY.equals(key)) {
                otelJavaAgentSha256 = value;
            } else if (key.startsWith(PROVIDER_KEY_PREFIX)) {
                putProvider(providers, key, value);
            } else {
                throw invalid("unexpected key: " + key);
            }
        }

        if (!EXPECTED_FORMAT_VERSION.equals(formatVersion)) {
            throw invalid("format.version must be " + EXPECTED_FORMAT_VERSION);
        }

        if (otelJavaAgentSha256 == null || !SHA256_PATTERN.matcher(otelJavaAgentSha256).matches()) {
            throw invalid("otel.javaagent.sha256 must be a lowercase SHA-256 value");
        }

        if (providers.isEmpty()) {
            throw invalid("at least one provider is required");
        }

        return providerNames(providers);
    }

    private static Set<String> providerNames(Map<Integer, String> providers) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String previousName = null;
        for (int providerIndex = 0; providerIndex < providers.size(); providerIndex++) {
            String name = providers.get(providerIndex);
            if (name == null) {
                throw invalid("provider indexes must be contiguous from zero");
            }

            if (!JAVA_BINARY_NAME_PATTERN.matcher(name).matches()) {
                throw invalid("invalid provider Java binary name: " + name);
            }

            if (!names.add(name)) {
                throw invalid("duplicate provider: " + name);
            }

            if (previousName != null && previousName.compareTo(name) >= 0) {
                throw invalid("providers must be sorted by Java binary name");
            }
            previousName = name;
        }

        return Collections.unmodifiableSet(names);
    }

    private static void putProvider(Map<Integer, String> providers, String key, String value) {
        String indexText = key.substring(PROVIDER_KEY_PREFIX.length());
        if (indexText.isEmpty() || (indexText.length() > 1 && indexText.charAt(0) == '0')) {
            throw invalid("invalid provider index: " + indexText);
        }

        for (int index = 0; index < indexText.length(); index++) {
            char character = indexText.charAt(index);
            if (character < '0' || character > '9') {
                throw invalid("invalid provider index: " + indexText);
            }
        }

        int providerIndex;
        try {
            providerIndex = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
            throw invalid("invalid provider index: " + indexText, e);
        }

        if (providers.putIfAbsent(providerIndex, value) != null) {
            throw invalid("duplicate provider index: " + providerIndex);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("resource must be valid UTF-8", e);
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Invalid ACP pin resource: " + reason);
    }

    private static IllegalStateException invalid(String reason, Exception cause) {
        return new IllegalStateException("Invalid ACP pin resource: " + reason, cause);
    }
}
