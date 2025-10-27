package space.br1440.platform.leaderelection.lock.persistence.redis.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseOperationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@RequiredArgsConstructor
public class RedisScriptExecutor {

    private static final long MIN_TTL_SECONDS = 1;
    private static final long MAX_TTL_SECONDS = 300;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public LeaseOperationResult createLease(String leaseKey, String identity, Duration ttl, int leaseDuration) {
        validateTTL(ttl);

        long timestamp = Instant.now().toEpochMilli();
        String result = executeScript(
                LeaseScripts.CREATE_LEASE_SCRIPT,
                leaseKey,
                identity,
                String.valueOf(ttl.getSeconds()),
                String.valueOf(timestamp),
                String.valueOf(leaseDuration)
        );
        return parseResult(result, leaseKey, "CREATE", root -> parseCreateStatus(root, leaseKey));
    }

    public LeaseOperationResult updateLease(String leaseKey,
                                            String identity,
                                            long expectedVersion,
                                            Duration ttl,
                                            Integer newLeaseDuration) {
        validateTTL(ttl);

        long timestamp = Instant.now().toEpochMilli();
        String result = executeScript(
                LeaseScripts.UPDATE_LEASE_SCRIPT,
                leaseKey,
                identity,
                String.valueOf(expectedVersion),
                String.valueOf(ttl.getSeconds()),
                String.valueOf(timestamp),
                (newLeaseDuration != null) ? String.valueOf(newLeaseDuration) : ""
        );
        return parseResult(result, leaseKey, "UPDATE", root -> parseUpdateStatus(root, leaseKey, identity));
    }

    public LeaseOperationResult deleteLease(String leaseKey, String identity) {
        String result = executeScript(LeaseScripts.DELETE_LEASE_SCRIPT, leaseKey, identity);
        return parseResult(result, leaseKey, "DELETE", root -> parseDeleteStatus(root, leaseKey, identity));
    }

    public LeaseOperationResult getLeaseInfo(String leaseKey, Duration refreshTtl) {
        long refreshSeconds = (refreshTtl != null) ? refreshTtl.getSeconds() : 0;
        String result = executeScript(LeaseScripts.GET_LEASE_INFO_SCRIPT, leaseKey, String.valueOf(refreshSeconds));
        return parseResult(result, leaseKey, "GET", root -> parseGetStatus(root, leaseKey));
    }

    public LeaseOperationResult acquireOrRenewLease(String leaseKey, String identity, Duration ttl, int leaseDuration) {
        validateTTL(ttl);

        long timestamp = Instant.now().toEpochMilli();
        String result = executeScript(
                LeaseScripts.ACQUIRE_OR_RENEW_LEASE_SCRIPT,
                leaseKey,
                identity,
                String.valueOf(ttl.getSeconds()),
                String.valueOf(timestamp),
                String.valueOf(leaseDuration)
        );
        return parseResult(result, leaseKey, "ACQUIRE_OR_RENEW", root -> parseAcquireOrRenewStatus(root, leaseKey));
    }

    private String executeScript(String scriptText, String key, String... args) {
        RedisScript<String> script = new DefaultRedisScript<>(scriptText, String.class);
        List<String> keys = Collections.singletonList(key);
        return redisTemplate.execute(script, keys, (Object[]) args);
    }

    private void validateTTL(Duration ttl) {
        long seconds = ttl.getSeconds();
        if (seconds < MIN_TTL_SECONDS || seconds > MAX_TTL_SECONDS) {
            String message = String.format(
                    "TTL must be between %d and %d seconds, got: %d",
                    MIN_TTL_SECONDS, MAX_TTL_SECONDS, seconds
            );
            throw new IllegalArgumentException(message);
        }
    }

    private LeaseOperationResult parseResult(String jsonResult, String leaseKey, String operation,
                                             Function<JsonNode, LeaseOperationResult> statusParser) {
        try {
            JsonNode root = objectMapper.readTree(jsonResult);
            String status = root.get("status").asText();
            if ("error".equals(status)) {
                return createScriptError(root, operation, leaseKey);
            }

            LeaseOperationResult result = statusParser.apply(root);
            if (result != null) {
                return result;
            }

            return createUnknownStatusError(status, operation, leaseKey);
        } catch (JsonProcessingException e) {
            return createParseError(e, operation, leaseKey);
        }
    }

    private LeaseOperationResult parseCreateStatus(JsonNode root, String leaseKey) {
        Instant timestamp = Instant.now();
        String status = root.get("status").asText();

        return switch (status) {
            case "created" -> {
                long version = root.get("version").asLong();
                LeaseOperationResult.Success.LeaseData data = new LeaseOperationResult.Success.LeaseData(
                        null, version, null, timestamp, timestamp, 0, null
                );
                yield new LeaseOperationResult.Success("CREATE", timestamp, leaseKey, data);
            }
            case "conflict" -> createConflictFromBasicFields(root, leaseKey);
            default -> null;
        };
    }

    private LeaseOperationResult parseUpdateStatus(JsonNode root, String leaseKey, String identity) {
        Instant timestamp = Instant.now();
        String status = root.get("status").asText();

        return switch (status) {
            case "renewed" -> {
                long version = root.get("version").asLong();
                int transitions = root.get("transitions").asInt();
                LeaseOperationResult.Success.LeaseData data = new LeaseOperationResult.Success.LeaseData(
                        identity, version, null, null, timestamp, transitions, null
                );
                yield new LeaseOperationResult.Success("UPDATE", timestamp, leaseKey, data);
            }
            case "not_found" -> new LeaseOperationResult.NotFound("UPDATE", timestamp, leaseKey);
            case "ownership_mismatch", "version_mismatch" -> createConflictWithVersion(root, leaseKey);
            default -> null;
        };
    }

    private LeaseOperationResult parseDeleteStatus(JsonNode root, String leaseKey, String identity) {
        String status = root.get("status").asText();
        Instant timestamp = Instant.now();

        return switch (status) {
            case "deleted" -> {
                long version = root.get("version").asLong();
                LeaseOperationResult.Success.LeaseData data = new LeaseOperationResult.Success.LeaseData(
                        identity, version, null, null, null, null, null
                );
                yield new LeaseOperationResult.Success("DELETE", timestamp, leaseKey, data);
            }
            case "not_found" -> new LeaseOperationResult.NotFound("DELETE", timestamp, leaseKey);
            case "ownership_mismatch" -> createOwnershipConflict(root, leaseKey);
            default -> null;
        };
    }

    private LeaseOperationResult parseGetStatus(JsonNode root, String leaseKey) {
        Instant timestamp = Instant.now();
        String status = root.get("status").asText();

        return switch (status) {
            case "found" -> {
                String holder = root.get("holder").asText();
                long version = root.get("version").asLong();
                int leaseDuration = root.get("lease_duration").asInt();
                int transitions = root.get("transitions").asInt();
                int ttlRemaining = root.get("ttl_remaining").asInt();

                Instant acquireTime = null;
                Instant renewTime = null;
                if (root.has("acquire_time") && !root.get("acquire_time").isNull()) {
                    try {
                        long acquireMillis = Long.parseLong(root.get("acquire_time").asText());
                        acquireTime = Instant.ofEpochMilli(acquireMillis);
                    } catch (NumberFormatException e) {
                        // Fallback к текущему времени, если парсинг не удался
                        acquireTime = timestamp;
                    }
                }
                if (root.has("renew_time") && !root.get("renew_time").isNull()) {
                    try {
                        long renewMillis = Long.parseLong(root.get("renew_time").asText());
                        renewTime = Instant.ofEpochMilli(renewMillis);
                    } catch (NumberFormatException e) {
                        // Fallback к текущему времени, если парсинг не удался
                        renewTime = timestamp;
                    }
                }

                LeaseOperationResult.Success.LeaseData data = new LeaseOperationResult.Success.LeaseData(
                        holder, version, leaseDuration, acquireTime, renewTime, transitions, ttlRemaining
                );
                yield new LeaseOperationResult.Success("GET", timestamp, leaseKey, data);
            }
            case "not_found" -> new LeaseOperationResult.NotFound("GET", timestamp, leaseKey);
            default -> null;
        };
    }

    private LeaseOperationResult parseAcquireOrRenewStatus(JsonNode root, String leaseKey) {
        Instant timestamp = Instant.now();
        String status = root.get("status").asText();

        return switch (status) {
            case "created", "renewed", "acquired" -> {
                long version = root.get("version").asLong();
                int transitions = root.get("transitions").asInt();
                LeaseOperationResult.Success.LeaseData data = new LeaseOperationResult.Success.LeaseData(
                        null, version, null, timestamp, timestamp, transitions, null
                );
                yield new LeaseOperationResult.Success("ACQUIRE_OR_RENEW", timestamp, leaseKey, data);
            }
            case "conflict" -> createConflictFromBasicFields(root, leaseKey);
            default -> null;
        };
    }

    private LeaseOperationResult createConflictFromBasicFields(JsonNode root, String leaseKey) {
        String currentHolder = root.get("current_holder").asText();
        Long currentVersion = root.get("current_version").asLong();
        Integer ttlRemaining = root.get("ttl_remaining").asInt();

        LeaseOperationResult.Conflict.ConflictDetails details = new LeaseOperationResult.Conflict.ConflictDetails(
                currentHolder, currentVersion, ttlRemaining, null
        );
        return new LeaseOperationResult.Conflict("CREATE", Instant.now(), leaseKey, details);
    }

    private LeaseOperationResult createConflictWithVersion(JsonNode root, String leaseKey) {
        String currentHolder = root.has("current_holder") ? root.get("current_holder").asText() : null;
        Long currentVersion = root.has("current_version") ? root.get("current_version").asLong() : null;
        Long expectedVersion = root.has("expected_version") ? root.get("expected_version").asLong() : null;

        LeaseOperationResult.Conflict.ConflictDetails details = new LeaseOperationResult.Conflict.ConflictDetails(
                currentHolder, currentVersion, 0, expectedVersion
        );
        return new LeaseOperationResult.Conflict("UPDATE", Instant.now(), leaseKey, details);
    }

    private LeaseOperationResult createOwnershipConflict(JsonNode root, String leaseKey) {
        String currentHolder = root.get("current_holder").asText();
        LeaseOperationResult.Conflict.ConflictDetails details = new LeaseOperationResult.Conflict.ConflictDetails(
                currentHolder, null, 0, null
        );
        return new LeaseOperationResult.Conflict("DELETE", Instant.now(), leaseKey, details);
    }

    private LeaseOperationResult createScriptError(JsonNode root, String operation, String leaseKey) {
        String error = root.get("error").asText();
        return new LeaseOperationResult.Failure(
                operation, Instant.now(), leaseKey,
                LeaseOperationResult.Failure.FailureReason.SCRIPT_ERROR,
                error, null
        );
    }

    private LeaseOperationResult createUnknownStatusError(String status, String operation, String leaseKey) {
        return new LeaseOperationResult.Failure(
                operation, Instant.now(), leaseKey,
                LeaseOperationResult.Failure.FailureReason.UNKNOWN,
                "Unknown status: " + status, null
        );
    }

    private LeaseOperationResult createParseError(JsonProcessingException e, String operation, String leaseKey) {
        return new LeaseOperationResult.Failure(
                operation, Instant.now(), leaseKey,
                LeaseOperationResult.Failure.FailureReason.PARSE_ERROR,
                "Failed to parse result: " + e.getMessage(), null
        );
    }

}
