package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import lombok.experimental.UtilityClass;

@UtilityClass
final class UpstreamCustomizerProviderAllowlist {

    static final String RESOURCE_PATH = "/META-INF/platform-tracing/otel-javaagent-acp-pin.properties";

    private static final int MAX_RESOURCE_BYTES = 16 * 1024;
    private static final String SUPPORTED_FORMAT_VERSION = "1";

    private static final String FORMAT_VERSION_KEY = "format.version";
    private static final String OTEL_JAVA_AGENT_SHA256_KEY = "otel.javaagent.sha256";
    private static final String PROVIDER_KEY_PREFIX = "provider.";

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PROVIDER_INDEX = Pattern.compile("0|[1-9][0-9]{0,8}");
    private static final Pattern JAVA_BINARY_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    record Pin(String otelJavaAgentSha256, Set<String> providerClassNames) {
    }

    static Pin load() {
        try (InputStream input = UpstreamCustomizerProviderAllowlist.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Upstream customizer provider pin is missing: " + RESOURCE_PATH);
            }
            return load(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read upstream customizer provider pin: " + RESOURCE_PATH,
                    exception
            );
        }
    }

    static Pin load(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw invalid("resource exceeds " + MAX_RESOURCE_BYTES + " bytes");
            }
            return parse(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to read upstream customizer provider pin: " + RESOURCE_PATH,
                    exception);
        }
    }

    static Pin parse(byte[] bytes) {
        String content = decodeStrictUtf8(bytes);
        if (content.indexOf('\r') >= 0) {
            throw invalid("CR characters are not allowed");
        }

        if (!content.endsWith("\n")) {
            throw invalid("resource must end with LF");
        }

        String formatVersion = null;
        String otelJavaAgentSha256 = null;
        TreeMap<Integer, String> providersByIndex = new TreeMap<>();
        Set<String> seenKeys = new HashSet<>();

        List<String> lines = content.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = (i + 1);

            Entry entry = Entry.parse(lines.get(i), lineNumber);
            if (!seenKeys.add(entry.key())) {
                throw invalid("duplicate key on line " + lineNumber + ": " + entry.key());
            }

            switch (entry.key()) {
                case FORMAT_VERSION_KEY -> formatVersion = entry.value();
                case OTEL_JAVA_AGENT_SHA256_KEY -> otelJavaAgentSha256 = entry.value();
                default -> providersByIndex.put(providerIndex(entry.key(), lineNumber), entry.value());
            }
        }

        if (!SUPPORTED_FORMAT_VERSION.equals(formatVersion)) {
            throw invalid(FORMAT_VERSION_KEY + " must be " + SUPPORTED_FORMAT_VERSION);
        }

        if (otelJavaAgentSha256 == null || !LOWERCASE_SHA256.matcher(otelJavaAgentSha256).matches()) {
            throw invalid(OTEL_JAVA_AGENT_SHA256_KEY + " must be a lowercase SHA-256 value");
        }

        return new Pin(otelJavaAgentSha256, providerClassNames(providersByIndex));
    }

    private static int providerIndex(String key, int lineNumber) {
        if (!key.startsWith(PROVIDER_KEY_PREFIX)) {
            throw invalid("unexpected key on line " + lineNumber + ": " + key);
        }

        String indexText = key.substring(PROVIDER_KEY_PREFIX.length());
        if (!PROVIDER_INDEX.matcher(indexText).matches()) {
            throw invalid("invalid provider index on line " + lineNumber + ": " + indexText);
        }

        return Integer.parseInt(indexText);
    }

    private static Set<String> providerClassNames(TreeMap<Integer, String> providersByIndex) {
        if (providersByIndex.isEmpty()) {
            throw invalid("at least one provider is required");
        }

        if (providersByIndex.lastKey() != providersByIndex.size() - 1) {
            throw invalid("provider indexes must be contiguous from zero");
        }

        Set<String> names = new LinkedHashSet<>();
        String previous = null;
        for (String name : providersByIndex.values()) {
            if (!JAVA_BINARY_NAME.matcher(name).matches()) {
                throw invalid("invalid provider class name: " + name);
            }

            if (previous != null && previous.compareTo(name) >= 0) {
                throw invalid("providers must be strictly sorted by class name: " + previous + " before " + name);
            }

            names.add(name);
            previous = name;
        }

        return Collections.unmodifiableSet(names);
    }

    private static String decodeStrictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalid("resource must be valid UTF-8", exception);
        }
    }

    private static IllegalStateException invalid(String reason) {
        return invalid(reason, null);
    }

    private static IllegalStateException invalid(String reason, Exception cause) {
        return new IllegalStateException(
                "Invalid upstream customizer provider pin " + RESOURCE_PATH + ": " + reason,
                cause
        );
    }

    private record Entry(String key, String value) {

        static Entry parse(String line, int lineNumber) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=') || separator == line.length() - 1) {
                throw invalid("line " + lineNumber + " must contain exactly one non-empty key=value pair");
            }

            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.equals(key.strip()) || !value.equals(value.strip())) {
                throw invalid("whitespace around key or value is not allowed on line " + lineNumber);
            }
            return new Entry(key, value);
        }
    }
}
