package space.br1440.platform.errorhandling.core.enricher;

import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.http.UpstreamIdentity;
import space.br1440.platform.errorhandling.core.util.http.UpstreamIntegrationDomain;
import space.br1440.platform.errorhandling.core.util.http.UpstreamOutcome;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class UpstreamIdentityEnricher implements ErrorEntryEnricher {

    private static final String UPSTREAM_STATUS_KEY = "upstreamStatus";
    private static final String UPSTREAM_ERROR_CODE_KEY = "upstreamErrorCode";

    @Override
    public int getOrder() {
        return (Ordered.HIGHEST_PRECEDENCE + 200);
    }

    @Override
    @NonNull
    public ErrorEntry enrich(@NonNull ErrorEntry entry, @NonNull ErrorHandlingContext context) {
        Optional<UpstreamOutcome> outcomeOpt = context.getUpstreamOutcome();
        if (outcomeOpt.isEmpty()) {
            return entry;
        }

        UpstreamOutcome outcome = outcomeOpt.get();
        if (outcome.httpStatus().isEmpty()) {
            return entry;
        }

        List<ErrorDetail> originalDetails = entry.getDetails();
        int targetIndex = findFirstHttpIntegrationDetail(originalDetails);
        if (targetIndex < 0) {
            return entry;
        }

        ErrorInfoDetail original = (ErrorInfoDetail) originalDetails.get(targetIndex);
        ErrorInfoDetail updated = applyOutcome(original, outcome);

        List<ErrorDetail> updatedDetails = new ArrayList<>(originalDetails);
        updatedDetails.set(targetIndex, updated);

        ErrorEntry.ErrorEntryBuilder entryBuilder = entry.toBuilder();
        Optional<String> resolvedDomain = outcome.identity().flatMap(UpstreamIdentity::domain);
        if (resolvedDomain.isPresent() && StringUtils.isNullOrBlank(entry.getDomain())) {
            entryBuilder.domain(resolvedDomain.get());
        }

        return entryBuilder
                .clearDetails()
                .details(Collections.unmodifiableList(updatedDetails))
                .build();
    }

    private static int findFirstHttpIntegrationDetail(@NonNull List<ErrorDetail> details) {
        for (int i = 0; i < details.size(); i++) {
            if (details.get(i) instanceof ErrorInfoDetail info
                    && UpstreamIntegrationDomain.HTTP_INTEGRATION.equals(info.getDomain())) {
                return i;
            }
        }

        return -1;
    }

    @NonNull
    private static ErrorInfoDetail applyOutcome(@NonNull ErrorInfoDetail original, @NonNull UpstreamOutcome outcome) {
        Map<String, String> clientMetadata = new LinkedHashMap<>(original.getClientMetadata());
        clientMetadata.put(UPSTREAM_STATUS_KEY, String.valueOf(outcome.httpStatus().get()));

        ErrorInfoDetail.ErrorInfoDetailBuilder builder = original.toBuilder()
                .clientMetadata(Collections.unmodifiableMap(clientMetadata));

        Optional<UpstreamIdentity> identityOpt = outcome.identity();
        if (identityOpt.isPresent()) {
            UpstreamIdentity identity = identityOpt.get();
            clientMetadata.put(UPSTREAM_ERROR_CODE_KEY, identity.errorCode());
            builder.clientMetadata(Collections.unmodifiableMap(clientMetadata));
            identity.domain().ifPresent(builder::domain);
        }

        return builder.build();
    }
}
