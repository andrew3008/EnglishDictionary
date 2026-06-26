package space.br1440.platform.errorhandling.testing.assertions;

import org.assertj.core.api.AbstractAssert;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.Map;
import java.util.Objects;

import org.assertj.core.api.Assertions;

/**
 * Assertion для верификации dual-metadata контракта CWE-209 на уровне {@link ErrorInfoDetail}.
 *
 * <p>Поддерживает навигацию обратно к {@link ErrorEntryAssert} через {@link #backToEntry()}.
 */
public class DualMetadataAssert extends AbstractAssert<DualMetadataAssert, ErrorInfoDetail> {

    @Nullable
    private final ErrorEntryAssert origin;

    DualMetadataAssert(ErrorInfoDetail actual, @Nullable ErrorEntryAssert origin) {
        super(actual, DualMetadataAssert.class);
        this.origin = origin;
    }

    public DualMetadataAssert(ErrorInfoDetail actual) {
        this(actual, null);
    }

    public static DualMetadataAssert assertThat(ErrorInfoDetail actual) {
        return new DualMetadataAssert(actual);
    }

    public DualMetadataAssert hasReason(String expected) {
        isNotNull();
        if (!Objects.equals(actual.getReason(), expected)) {
            failWithMessage("Expected reason <%s> but was <%s>", expected, actual.getReason());
        }

        return myself;
    }

    public DualMetadataAssert hasDomain(String expected) {
        isNotNull();
        if (!Objects.equals(actual.getDomain(), expected)) {
            failWithMessage("Expected domain <%s> but was <%s>", expected, actual.getDomain());
        }

        return myself;
    }

    public DualMetadataAssert clientMetadataContains(String key, String value) {
        isNotNull();
        Assertions.assertThat(actual.getClientMetadata())
                .as("clientMetadata[%s]", key)
                .containsEntry(key, value);

        return myself;
    }

    public DualMetadataAssert clientMetadataDoesNotContain(String... keys) {
        isNotNull();
        Map<String, String> meta = actual.getClientMetadata();
        for (String key : keys) {
            if (meta.containsKey(key)) {
                failWithMessage("clientMetadata must NOT contain key <%s>, but it does: <%s>",
                        key, meta.get(key));
            }
        }

        return myself;
    }

    public DualMetadataAssert diagnosticMetadataContains(String key, String value) {
        isNotNull();
        Assertions.assertThat(actual.getDiagnosticMetadata())
                .as("diagnosticMetadata[%s]", key)
                .containsEntry(key, value);

        return myself;
    }

    /**
     * Прямая верификация CWE-209: diagnosticMetadata ключи не должны
     * присутствовать в clientMetadata.
     */
    public DualMetadataAssert diagnosticMetadataIsNotExposedToClient() {
        isNotNull();
        Map<String, String> client = actual.getClientMetadata();
        Map<String, String> diagnostic = actual.getDiagnosticMetadata();

        for (String diagnosticKey : diagnostic.keySet()) {
            if (client.containsKey(diagnosticKey)) {
                failWithMessage(
                        "CWE-209 violation: diagnosticMetadata key <%s> leaked into clientMetadata. " +
                                "Value in client: <%s>, value in diagnostic: <%s>",
                        diagnosticKey, client.get(diagnosticKey), diagnostic.get(diagnosticKey));
            }
        }

        return myself;
    }

    /**
     * Навигация обратно к родительскому {@link ErrorEntryAssert}.
     */
    public ErrorEntryAssert backToEntry() {
        if (origin == null) {
            throw new IllegalStateException(
                    "backToEntry() допустим только при навигации из assertThat(ErrorEntry)");
        }

        return origin;
    }

}
