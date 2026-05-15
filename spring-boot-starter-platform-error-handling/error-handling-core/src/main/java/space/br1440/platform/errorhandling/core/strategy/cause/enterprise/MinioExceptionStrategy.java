package space.br1440.platform.errorhandling.core.strategy.cause.enterprise;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.*;

import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;

import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.*;

/**
 * Стратегия обработки исключений MinIO Object Storage (и S3-совместимых хранилищ)
 * как первопричины бизнес-исключений.
 *
 * <h2>HTTP-маппинг</h2>
 * <ul>
 *   <li>Объект или бакет не найден ({@code NoSuchKey}, {@code NoSuchBucket} и др.) → <b>404 Not Found</b></li>
 *   <li>Доступ запрещён ({@code AccessDenied}, {@code AllAccessDisabled}) → <b>403 Forbidden</b></li>
 *   <li>Конфигурационные ошибки ({@code InvalidAccessKeyId}, {@code SignatureDoesNotMatch},
 *       {@code RequestTimeTooSkewed}, {@code KMSDisabledException}) → <b>500 Internal Server Error</b></li>
 *   <li>Конфликт ресурса ({@code BucketAlreadyExists}) → <b>409 Conflict</b></li>
 *   <li>Нарушение предусловия ({@code BucketNotEmpty}, {@code InvalidObjectState}) → <b>409 Conflict</b></li>
 *   <li>Таймаут ({@code RequestTimeout}) → <b>504 Gateway Timeout</b></li>
 *   <li>Хранилище недоступно ({@code SlowDown}, {@code ServiceUnavailable}) → <b>503 Service Unavailable</b></li>
 *   <li>Прерванная загрузка ({@code InsufficientDataException}) → <b>400 Bad Request</b></li>
 *   <li>Ошибка SDK / конфигурации ключей → <b>500 Internal Server Error</b></li>
 *   <li>Невалидный ответ хранилища → <b>502 Bad Gateway</b></li>
 * </ul>
 */
public class MinioExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 300;

    private static final Set<String> SDK_SAFE_KEYWORDS = Set.of(
            "connection", "timeout", "refused", "reset", "closed",
            "eof", "broken pipe", "network", "dns", "resolve",
            "ssl", "tls", "handshake", "certificate"
    );

    private static final ClassLoader LOADER = MinioExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> MINIO_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.MinioException", LOADER);

    @Nullable
    private static final Class<?> ERROR_RESPONSE_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.ErrorResponseException", LOADER);

    @Nullable
    private static final Class<?> INSUFFICIENT_DATA_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.InsufficientDataException", LOADER);

    @Nullable
    private static final Class<?> INTERNAL_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.InternalException", LOADER);

    @Nullable
    private static final Class<?> INVALID_KEY_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.InvalidKeyException", LOADER);

    @Nullable
    private static final Class<?> INVALID_RESPONSE_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.InvalidResponseException", LOADER);

    @Nullable
    private static final Class<?> XML_PARSER_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.XmlParserException", LOADER);

    @Nullable
    private static final Class<?> BUCKET_POLICY_TOO_LARGE_EXCEPTION_CLASS =
            ReflectionUtils.loadOptionalClass("io.minio.errors.BucketPolicyTooLargeException", LOADER);

    public MinioExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.MINIO, stackHintExtractor);
    }

    private static final Set<String> NOT_FOUND_CODES = Set.of(
            "NoSuchKey",
            "NoSuchBucket",
            "NoSuchUpload",
            "NoSuchBucketPolicy",
            "NoSuchCORSConfiguration",
            "NoSuchLifecycleConfiguration",
            "NoSuchReplicationConfiguration",
            "NoSuchTagSet",
            "ObjectLockConfigurationNotFoundError"
    );

    private static final Set<String> FORBIDDEN_CODES = Set.of(
            "AccessDenied",
            "AllAccessDisabled",
            "PublicAccessBlockedAll"
    );

    private static final Set<String> CONFIG_ERROR_CODES = Set.of(
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "InvalidSecurity",
            "RequestTimeTooSkewed",
            "KMSDisabledException"
    );

    private static final Set<String> CONFLICT_EXISTS_CODES = Set.of(
            "BucketAlreadyExists",
            "BucketAlreadyOwnedByYou"
    );

    private static final Set<String> BUSINESS_PRECONDITION_CODES = Set.of(
            "BucketNotEmpty",
            "InvalidObjectState",
            "OperationAborted"
    );

    private static final Set<String> HTTP_PRECONDITION_CODES = Set.of(
            "PreconditionFailed"
    );

    private static final Set<String> TIMEOUT_CODES = Set.of(
            "RequestTimeout"
    );

    private static final Set<String> UNAVAILABLE_CODES = Set.of(
            "SlowDown",
            "ServiceUnavailable"
    );

    private static final Set<String> NOT_IMPLEMENTED_CODES = Set.of(
            "NotImplemented"
    );

    private static final Set<String> INVALID_RANGE_CODES = Set.of(
            "InvalidRange"
    );

    private static final Set<String> QUOTA_EXCEEDED_CODES = Set.of(
            "QuotaExceeded"
    );

    private static final Set<String> PAYLOAD_TOO_LARGE_CODES = Set.of(
            "EntityTooLarge",
            "MaxMessageLengthExceeded"
    );

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(MINIO_EXCEPTION_CLASS, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        ClassificationResult result = classifyException(cause);
        MinioErrorType errorType = result.errorType();

        List<ErrorDetail> details = new ArrayList<>();
        if (errorType.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(errorType.name())
                .domain("object-storage")
                .clientMetadata(buildClientMetadata(errorType, result.errorInfo()))
                .diagnosticMetadata(buildDiagnosticMetadata(cause, result))
                .build();
        details.add(info);

        return errorType.toResolution(details);
    }

    @NonNull
    private static ClassificationResult classifyException(@NonNull Throwable exception) {
        if (ClassUtils.isInstanceOf(INSUFFICIENT_DATA_EXCEPTION_CLASS, exception)) {
            return new ClassificationResult(MinioErrorType.UPLOAD_ABORTED, null);
        }

        if (ClassUtils.isInstanceOf(INTERNAL_EXCEPTION_CLASS, exception)) {
            return new ClassificationResult(MinioErrorType.SDK_ERROR, null);
        }

        if (ClassUtils.isInstanceOf(INVALID_KEY_EXCEPTION_CLASS, exception)) {
            return new ClassificationResult(MinioErrorType.CONFIG_ERROR, null);
        }

        if (ClassUtils.isInstanceOf(BUCKET_POLICY_TOO_LARGE_EXCEPTION_CLASS, exception)) {
            return new ClassificationResult(MinioErrorType.POLICY_TOO_LARGE, null);
        }

        if (ClassUtils.isInstanceOf(INVALID_RESPONSE_EXCEPTION_CLASS, exception)) {
            return new ClassificationResult(MinioErrorType.UPSTREAM_ERROR, null);
        }

        if (ClassUtils.isInstanceOf(XML_PARSER_EXCEPTION_CLASS, exception)) {
            return new ClassificationResult(MinioErrorType.UPSTREAM_ERROR, null);
        }

        if (ClassUtils.isInstanceOf(ERROR_RESPONSE_EXCEPTION_CLASS, exception)) {
            MinioErrorInfo info = extractMinioErrorInfo(exception);
            MinioErrorType errorType = classifyByCode(info.errorCode());
            return new ClassificationResult(errorType, info);
        }

        return new ClassificationResult(MinioErrorType.UPSTREAM_ERROR, null);
    }

    @NonNull
    private static MinioErrorType classifyByCode(@Nullable String code) {
        if (!StringUtils.hasText(code)) {
            return MinioErrorType.UPSTREAM_ERROR;
        }

        if (NOT_FOUND_CODES.contains(code)) return MinioErrorType.NOT_FOUND;
        if (FORBIDDEN_CODES.contains(code)) return MinioErrorType.ACCESS_DENIED;
        if (CONFIG_ERROR_CODES.contains(code)) return MinioErrorType.CONFIG_ERROR;
        if (CONFLICT_EXISTS_CODES.contains(code)) return MinioErrorType.ALREADY_EXISTS;
        if (BUSINESS_PRECONDITION_CODES.contains(code)) return MinioErrorType.FAILED_PRECONDITION;
        if (HTTP_PRECONDITION_CODES.contains(code)) return MinioErrorType.PRECONDITION_FAILED;
        if (PAYLOAD_TOO_LARGE_CODES.contains(code)) return MinioErrorType.PAYLOAD_TOO_LARGE;
        if (TIMEOUT_CODES.contains(code)) return MinioErrorType.TIMEOUT;
        if (UNAVAILABLE_CODES.contains(code)) return MinioErrorType.UNAVAILABLE;
        if (NOT_IMPLEMENTED_CODES.contains(code)) return MinioErrorType.NOT_IMPLEMENTED;
        if (INVALID_RANGE_CODES.contains(code)) return MinioErrorType.RANGE_NOT_SATISFIABLE;
        if (QUOTA_EXCEEDED_CODES.contains(code)) return MinioErrorType.QUOTA_EXCEEDED;

        return MinioErrorType.UPSTREAM_ERROR;
    }

    @NonNull
    private static Map<String, String> buildClientMetadata(@NonNull MinioErrorType errorType,
                                                           @Nullable MinioErrorInfo errorInfo) {
        Map<String, String> meta = new LinkedHashMap<>();

        if (errorInfo != null && StringUtils.hasText(errorInfo.errorCode())) {
            meta.put("storageErrorCode", errorInfo.errorCode());
        }

        return Collections.unmodifiableMap(meta);
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause,
                                                        @NonNull ClassificationResult result) {
        MinioErrorType errorType = result.errorType();
        boolean isConfigOrSdkError = (errorType == MinioErrorType.CONFIG_ERROR || errorType == MinioErrorType.SDK_ERROR);

        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor);
        builder.put("exceptionClass", cause.getClass().getName());
        builder.putIfPresent("causeMessage", StringUtils.truncate(cause.getMessage(), MAX_CAUSE_MESSAGE_LENGTH));
        builder.withRootCause(CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH, MAX_CAUSE_MESSAGE_LENGTH);

        if (!isConfigOrSdkError && result.errorInfo() != null) {
            applyMinioErrorFields(result.errorInfo(), builder);
        }

        enrichWithErrorResponseDetails(cause, isConfigOrSdkError, builder);

        if (!isConfigOrSdkError) {
            enrichWithHttpStatusCode(cause, builder);
        }

        enrichByErrorType(cause, errorType, builder);

        return builder.withStackHint().build();
    }

    /**
     * Извлекает структурные S3-данные из {@code ErrorResponseException} через reflection.
     * <p>
     * CWE-209: {@code objectName} и {@code region} теперь извлекаются, но только
     * для {@code diagnosticMetadata} (SRE-only, не попадает в DTO по design by contract).
     */
    @NonNull
    private static MinioErrorInfo extractMinioErrorInfo(@NonNull Throwable exception) {
        final String[] code = {null};
        final String[] bucket = {null};

        ReflectionUtils.invokeMethod(exception, "errorResponse", errorResponse -> {
            if (errorResponse == null) {
                return;
            }

            ReflectionUtils.invokeMethod(errorResponse, "code", c -> {
                if (c instanceof String s && StringUtils.hasText(s)) {
                    code[0] = s;
                }
            });

            ReflectionUtils.invokeMethod(errorResponse, "bucketName", b -> {
                if (b instanceof String s && StringUtils.hasText(s)) {
                    bucket[0] = s;
                }
            });
        });

        return new MinioErrorInfo(code[0], bucket[0]);
    }

    private static void applyMinioErrorFields(@NonNull MinioErrorInfo info,
                                              @NonNull DiagnosticMetadataBuilder builder) {
        if (StringUtils.hasText(info.errorCode())) {
            builder.put("storageErrorCode", info.errorCode());
        }

        if (StringUtils.hasText(info.bucket())) {
            builder.put("bucket", info.bucket());
        }
    }

    /**
     * Извлекает расширенные данные из {@code ErrorResponseException.errorResponse()}.
     *
     * <p>Цепочка вызовов (через reflection):
     * <pre>
     * exception.errorResponse()   → ErrorResponse
     *   .message()                → String (human-readable S3 error)
     *   .objectName()             → String (S3 object key, SRE-only)
     *   .resource()               → String (S3 resource path, SRE-only)
     *   .requestId()              → String (S3 request ID)
     * </pre>
     *
     * <p>Для CONFIG_ERROR/SDK_ERROR: только correlation ID ({@code s3RequestId}).
     * S3 error details ограничены — могут содержать конфигурационные данные.
     */
    private static void enrichWithErrorResponseDetails(@NonNull Throwable cause,
                                                       boolean restrictDetails,
                                                       @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "errorResponse", errorResponse -> {
            if (errorResponse == null) {
                return;
            }

            ReflectionUtils.invokeMethod(errorResponse, "requestId", requestId -> {
                if (requestId instanceof String s && StringUtils.hasText(s)) {
                    builder.put("s3RequestId", s);
                }
            });

            if (restrictDetails) {
                return;
            }

            ReflectionUtils.invokeMethod(errorResponse, "message", msg -> {
                if (msg instanceof String s3ErrorMessage) {
                    builder.putTruncatedIfPresent("s3ErrorMessage", s3ErrorMessage, MAX_CAUSE_MESSAGE_LENGTH);
                }
            });

            ReflectionUtils.invokeMethod(errorResponse, "objectName", objName -> {
                if (objName instanceof String s && StringUtils.hasText(s)) {
                    builder.put("objectName", s);
                }
            });

            ReflectionUtils.invokeMethod(errorResponse, "resource", resource -> {
                if (resource instanceof String s && StringUtils.hasText(s)) {
                    builder.put("s3Resource", s);
                }
            });
        });
    }

    /**
     * <p>MinIO SDK 8.5+: {@code ErrorResponseException} наследует {@code ServerException},
     * который хранит HTTP status code из ответа хранилища.
     * В более ранних версиях метод отсутствует — reflection gracefully возвращает {@code null}.
     */
    private static void enrichWithHttpStatusCode(@NonNull Throwable cause,
                                                 @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "statusCode", statusCode -> {
            if (statusCode instanceof Integer i && i > 0) {
                builder.put("s3HttpStatusCode", i.toString());
            }
        });
    }

    private static void enrichByErrorType(@NonNull Throwable cause,
                                          @NonNull MinioErrorType errorType,
                                          @NonNull DiagnosticMetadataBuilder builder) {
        switch (errorType) {
            case UPLOAD_ABORTED -> enrichUploadAborted(cause, builder);
            case SDK_ERROR -> enrichSdkError(cause, builder);
            default -> {
            }
        }
    }

    private static void enrichUploadAborted(@NonNull Throwable cause,
                                            @NonNull DiagnosticMetadataBuilder builder) {
        ReflectionUtils.invokeMethod(cause, "available", available -> {
            if (available instanceof Number n) {
                builder.put("bytesReceived", Long.toString(n.longValue()));
            }
        });

        ReflectionUtils.invokeMethod(cause, "expected", expected -> {
            if (expected instanceof Number n) {
                builder.put("bytesExpected", Long.toString(n.longValue()));
            }
        });
    }

    /**
     * Для {@link MinioErrorType#SDK_ERROR} ({@code InternalException}) —
     * извлекает безопасный {@code sdkErrorMessage}, пропуская сообщения,
     * которые могут содержать endpoint URLs, credentials или иные secrets.
     */
    private static void enrichSdkError(@NonNull Throwable cause,
                                       @NonNull DiagnosticMetadataBuilder builder) {
        Throwable deepest = CauseChainNavigator.findDeepestCause(cause, CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);

        String deepestMessage = deepest.getMessage();
        if (!StringUtils.hasText(deepestMessage)) {
            return;
        }

        String lower = deepestMessage.toLowerCase();
        boolean safe = SDK_SAFE_KEYWORDS.stream().anyMatch(lower::contains);
        if (safe) {
            builder.putTruncated("sdkErrorMessage", deepestMessage, MAX_CAUSE_MESSAGE_LENGTH);
        } else {
            builder.put("sdkErrorMessage", deepest.getClass().getName());
        }
    }

    private enum MinioErrorType implements ErrorClassification {
        NOT_FOUND(
                HttpStatus.NOT_FOUND,
                Retryability.NOT_RETRYABLE,
                "The requested file or bucket does not exist in the storage."),

        ACCESS_DENIED(
                HttpStatus.FORBIDDEN,
                Retryability.NOT_RETRYABLE,
                "Access to the requested storage resource is denied."),

        CONFIG_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "The storage service is not available due to a server configuration issue."),

        SDK_ERROR(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Retryability.NOT_RETRYABLE,
                "An internal error occurred in the storage SDK."),

        ALREADY_EXISTS(
                HttpStatus.CONFLICT,
                Retryability.NOT_RETRYABLE,
                "The requested storage resource already exists."),

        FAILED_PRECONDITION(
                HttpStatus.CONFLICT,
                Retryability.NOT_RETRYABLE,
                "The storage operation cannot be completed due to a precondition failure."),

        PRECONDITION_FAILED(
                HttpStatus.PRECONDITION_FAILED,
                Retryability.NOT_RETRYABLE,
                "The storage operation failed because a precondition (ETag or Last-Modified) was not met."),

        PAYLOAD_TOO_LARGE(
                HttpStatus.PAYLOAD_TOO_LARGE,
                Retryability.NOT_RETRYABLE,
                "The uploaded file exceeds the maximum allowed object size."),

        POLICY_TOO_LARGE(
                HttpStatus.BAD_REQUEST,
                Retryability.NOT_RETRYABLE,
                "The bucket policy document exceeds the maximum allowed size."),

        UPLOAD_ABORTED(
                HttpStatus.BAD_REQUEST,
                Retryability.NOT_RETRYABLE,
                "The upload stream ended before the expected size was reached. The upload may have been aborted."),

        TIMEOUT(
                HttpStatus.GATEWAY_TIMEOUT,
                Retryability.RETRYABLE,
                "The storage service did not respond within the configured timeout."),

        UNAVAILABLE(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The storage service is temporarily unavailable. Retry the request."),

        NOT_IMPLEMENTED(
                HttpStatus.NOT_IMPLEMENTED,
                Retryability.NOT_RETRYABLE,
                "The requested storage operation is not supported by the storage server."),

        RANGE_NOT_SATISFIABLE(
                HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                Retryability.NOT_RETRYABLE,
                "The requested byte range is outside the bounds of the storage object."),

        QUOTA_EXCEEDED(
                HttpStatus.INSUFFICIENT_STORAGE,
                Retryability.NOT_RETRYABLE,
                "The storage quota has been exceeded. No more objects can be stored."),

        /**
         * @implNote Fallback покрывает как transient ошибки (неизвестные S3 error codes),
         * так и детерминистические (XmlParserException, InvalidResponseException — ошибки
         * парсинга ответа SDK). retryable=true — оптимистичное допущение: большинство
         * неклассифицированных S3-ошибок transient. Для парсинг-ошибок retry может быть
         * бесполезен, но выделение отдельного типа не оправдано по cost/value.
         */
        UPSTREAM_ERROR(
                HttpStatus.BAD_GATEWAY,
                Retryability.RETRYABLE,
                "The storage service returned an invalid or unexpected response.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        MinioErrorType(@NonNull HttpStatus httpStatus,
                       @NonNull Retryability retryability,
                       @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @NonNull
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @NonNull
        public Retryability retryability() {
            return retryability;
        }

        @NonNull
        public String message() {
            return message;
        }
    }

    private record ClassificationResult(@NonNull MinioErrorType errorType,
                                        @Nullable MinioErrorInfo errorInfo) {
    }

    private record MinioErrorInfo(@Nullable String errorCode,
                                  @Nullable String bucket) {
    }
}
