package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import space.br1440.platform.common.util.StringUtils;

public final class MaskedSecret {

    private static final MaskedSecret ABSENT = new MaskedSecret(null);

    private final byte[] value;

    private MaskedSecret(byte[] value) {
        this.value = value;
    }

    public static MaskedSecret of(String rawValue) {
        if (StringUtils.isNullOrBlank(rawValue)) {
            return ABSENT;
        }

        return new MaskedSecret(rawValue.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isPresent() {
        return (value != null);
    }

    public byte[] copyBytes() {
        return (value == null) ? null : Arrays.copyOf(value, value.length);
    }

    @Override
    public String toString() {
        return isPresent() ? "MaskedSecret[present]" : "MaskedSecret[absent]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof MaskedSecret that)) {
            return false;
        }

        return (isPresent() == that.isPresent());
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(isPresent());
    }
}
