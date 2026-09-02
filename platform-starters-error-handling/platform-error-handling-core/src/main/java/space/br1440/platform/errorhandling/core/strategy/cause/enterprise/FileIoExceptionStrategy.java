package space.br1440.platform.errorhandling.core.strategy.cause.enterprise;

import com.fasterxml.jackson.core.JsonParseException;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.CauseChainNavigator;
import space.br1440.platform.errorhandling.core.util.CauseMetadataEnricher;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;

import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.io.*;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Стратегия обработки файлового ввода/вывода (JDK standard library).
 *
 * <h2>Поддерживаемые исключения</h2>
 * <pre>
 *   java.io.IOException                          → 503  GENERIC         (generic fallback)
 *   ├── FileNotFoundException                    → 404  FILE_NOT_FOUND  (legacy API)
 *   ├── EOFException                             → 422  CONTENT_TRUNCATED
 *   ├── StreamCorruptedException                 → 422  CONTENT_CORRUPTED
 *   ├── CharConversionException                  → 400  INVALID_ENCODING
 *   ├── UnsupportedEncodingException             → 500  CONFIG_ERROR
 *   ├── InterruptedIOException                   → 503  INTERRUPTED
 *   │   └── SocketTimeoutException               → 504  SOCKET_TIMEOUT   (явный case)
 *   └── FileSystemException  (nio base)          → 503  FILESYSTEM_ERROR (generic NIO)
 *       ├── NoSuchFileException                  → 404  FILE_NOT_FOUND
 *       ├── AccessDeniedException                → 403  ACCESS_DENIED
 *       ├── FileAlreadyExistsException           → 409  FILE_ALREADY_EXISTS
 *       ├── DirectoryNotEmptyException           → 409  DIRECTORY_NOT_EMPTY
 *       └── (прочие: NotDirectoryException,      → 503  FILESYSTEM_ERROR
 *              FileSystemLoopException, etc.)
 *
 *   java.nio.channels.ClosedChannelException    → 503  CHANNEL_CLOSED
 * </pre>
 *
 * <h2>HTTP-семантика</h2>
 * <ul>
 *   <li><b>404</b> — {@code NoSuchFileException}, {@code FileNotFoundException}:
 *       файл/ресурс не существует.</li>
 *   <li><b>403</b> — {@code AccessDeniedException}: нет прав доступа к файлу.
 *       <em>Примечание:</em> в большинстве enterprise-сценариев (user-specific storage,
 *       ACL на файлах) это клиентская ошибка. Если процесс не имеет прав на
 *       собственные файлы — это проблема конфигурации сервера, но 403 остаётся
 *       наиболее информативным ответом для consumer'а.</li>
 *   <li><b>409</b> — {@code FileAlreadyExistsException}: нарушение уникальности
 *       (файл уже существует); {@code DirectoryNotEmptyException}: директория
 *       не может быть удалена.</li>
 *   <li><b>422</b> — {@code EOFException}: файл усечён или содержит неожиданный
 *       конец потока — контент невалиден. <em>Примечание:</em> 422 оправдан при
 *       обработке загруженного пользователем файла (EOF = файл повреждён при передаче).
 *       Для filesystem storage это инфраструктурная проблема, но 422 остаётся
 *       наиболее информативным ответом для consumer'а.
 *       {@code StreamCorruptedException}: файл повреждён (corrupted stream).</li>
 *   <li><b>504</b> — {@code SocketTimeoutException}: таймаут сокета — upstream
 *       не ответил вовремя (не путать с {@link InterruptedIOException}).</li>
 *   <li><b>500</b> — {@code UnsupportedEncodingException}: ошибка конфигурации приложения (encoding недоступен).</li>
 *   <li><b>400</b> — {@code CharConversionException}: ошибка кодировки символов в файле (неверный encoding).</li>
 *   <li><b>503</b> — прочие transient {@link IOException}: дисковые ошибки, filesystem сбои, прерывание операции, закрытый канал.</li>
 *   <li><b>500</b> — {@link IoErrorType#GENERIC} при детерминированной (non-transient) ошибке:
 *       retry бессмыслен, статус динамически понижается в {@link #resolveHttpStatus}
 *       с {@code 503} на {@code 500}. Downgrade применяется <em>только</em> к {@code GENERIC};
 *       для явных {@code CONFIG_ERROR}/{@code INVALID_ENCODING} HTTP-статус берётся как есть.</li>
 * </ul>
 *
 * <p><b>Безопасность (CWE-209):</b> намеренно <b>не включаются</b>:
 * <ul>
 *   <li>Полные пути файлов — могут раскрыть структуру файловой системы, имена
 *       внутренних директорий, логины пользователей из home paths;</li>
 *   <li>Сырое сообщение исключения <em>для filesystem-подтипов</em> ({@link FileSystemException},
 *       {@link FileNotFoundException}) — может содержать абсолютные пути. Root-cause chain
 *       добавляется <em>только</em> для {@link IoErrorType#GENERIC}, где filesystem-подтипы
 *       по определению отфильтрованы {@link #classify};</li>
 *   <li>Системные временные метки файлов.</li>
 * </ul>
 * Допускаются только усечённые безопасные поля ({@code fsReason}, {@code encodingName}) и
 * протокольно-технические значения без путей.
 */
public class FileIoExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int FS_REASON_MAX = 150;
    private static final int ENCODING_NAME_MAX = 50;

    /**
     * Допустимое расширение для metadata: буквы/цифры, без CR/LF и прочих символов (снижение риска log injection).
     */
    private static final Pattern SAFE_FILE_EXTENSION = Pattern.compile("^[a-zA-Z0-9]{1,10}$");

    /**
     * Допустимое имя кодировки: буквы, цифры, точка, подчёркивание, двоеточие, дефис.
     * {@link CharConversionException#getMessage()} может содержать произвольный текст,
     * в отличие от {@link UnsupportedEncodingException} (всегда charset name).
     * Верхняя граница длины совпадает с {@link #ENCODING_NAME_MAX}.
     */
    private static final Pattern SAFE_ENCODING_NAME =
            Pattern.compile("^[a-zA-Z0-9_.:-]{1," + ENCODING_NAME_MAX + "}$");

    private static final Set<String> NON_RETRYABLE_FS_REASONS = Set.of(
            "not a directory",
            "symbolic link",
            "file loop",
            "invalid argument",
            "operation not supported",
            "directory name is invalid",
            "not a reparse point",
            "parameter is incorrect"
    );

    public FileIoExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.FILE_IO, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return cause instanceof IOException;
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        IOException ioException = (IOException) cause;
        IoErrorType type = classify(ioException);

        boolean retryable = isRetryable(type, ioException);
        Retryability retryability = retryable ? Retryability.RETRYABLE : Retryability.NOT_RETRYABLE;

        List<ErrorDetail> details = new ArrayList<>();
        if (retryable) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain("file-io")
                .diagnosticMetadata(buildDiagnosticMetadata(ioException, type, retryable))
                .build();
        details.add(info);

        HttpStatus httpStatus = resolveHttpStatus(type, retryable);
        return RootCauseResolution.of(httpStatus, type.message(), details, retryability);
    }

    @NonNull
    private static HttpStatus resolveHttpStatus(@NonNull IoErrorType type, boolean retryable) {
        if (!retryable && type.httpStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return type.httpStatus();
    }

    @NonNull
    private static IoErrorType classify(@NonNull IOException ioException) {
        return switch (ioException) {
            // java.nio.file — специфичные подклассы FileSystemException
            case NoSuchFileException ignored -> IoErrorType.FILE_NOT_FOUND;
            case AccessDeniedException ignored -> IoErrorType.ACCESS_DENIED;
            case FileAlreadyExistsException ignored -> IoErrorType.FILE_ALREADY_EXISTS;
            case DirectoryNotEmptyException ignored -> IoErrorType.DIRECTORY_NOT_EMPTY;

            // Прочие FileSystemException (NotDirectoryException, NotLinkException,
            // FileSystemLoopException и т.д.): retry-решение в isRetryableFileSystemFailure
            case FileSystemException ignored -> IoErrorType.FILESYSTEM_ERROR;

            // java.io — legacy классы (порядок важен: SocketTimeoutException до InterruptedIOException)
            case FileNotFoundException ignored -> IoErrorType.FILE_NOT_FOUND;
            case EOFException ignored -> IoErrorType.CONTENT_TRUNCATED;
            case StreamCorruptedException ignored -> IoErrorType.CONTENT_CORRUPTED;
            // CharConversionException и UnsupportedEncodingException — независимые подклассы IOException,
            // порядок между ними не критичен
            case CharConversionException ignored -> IoErrorType.INVALID_ENCODING;
            case UnsupportedEncodingException ignored -> IoErrorType.CONFIG_ERROR;

            case JsonParseException ignored -> IoErrorType.INVALID_ENCODING;

            // java.net — подтип InterruptedIOException, должен быть первым
            case SocketTimeoutException ignored -> IoErrorType.SOCKET_TIMEOUT;
            case InterruptedIOException ignored -> IoErrorType.INTERRUPTED;

            // java.nio.channels
            case ClosedChannelException ignored -> IoErrorType.CHANNEL_CLOSED;

            default -> IoErrorType.GENERIC;
        };
    }

    /**
     * Metadata без полных путей и сырого {@code getMessage()} (CWE-209), кроме явно усечённых полей.
     */
    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull IOException cause,
                                                        @NonNull IoErrorType type,
                                                        boolean retryable) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        if (type == IoErrorType.GENERIC) {
            builder.put("retryableReason", retryable ? "TRANSIENT_NETWORK" : "NON_TRANSIENT");
        }

        if (cause instanceof FileSystemException fileSystemException) {
            String file = fileSystemException.getFile();
            if (StringUtils.hasText(file)) {
                putFileExtension(builder, "fileExtension", file);
            }

            String other = fileSystemException.getOtherFile();
            if (StringUtils.hasText(other)) {
                putFileExtension(builder, "targetFileExtension", other);
            }

            String reason = fileSystemException.getReason();
            if (StringUtils.hasText(reason)) {
                String truncated = StringUtils.truncate(reason, FS_REASON_MAX);
                if (truncated != null) {
                    builder.put("fsReason", truncated);
                }
            }
        }

        if (cause instanceof InterruptedIOException interruptedIo
                && interruptedIo.bytesTransferred > 0) {
            builder.put("bytesTransferred", String.valueOf(interruptedIo.bytesTransferred));
        }

        if (cause instanceof UnsupportedEncodingException || cause instanceof CharConversionException) {
            String causeMessage = cause.getMessage();
            if (StringUtils.hasText(causeMessage)) {
                String truncated = StringUtils.truncate(causeMessage, ENCODING_NAME_MAX);
                if (truncated != null && SAFE_ENCODING_NAME.matcher(truncated).matches()) {
                    builder.put("encodingName", truncated);
                }
            }
        }

        if (type == IoErrorType.GENERIC) {
            builder.withRootCause();
        }

        builder.withStackHint();

        if (type == IoErrorType.CHANNEL_CLOSED) {
            enrichChannelType(builder, stackHintExtractor.extractStackHint(cause));
        }

        return builder.build();
    }

    private static void putFileExtension(@NonNull DiagnosticMetadataBuilder builder,
                                         @NonNull String key,
                                         @NonNull String pathOrName) {
        int dotIndex = pathOrName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < (pathOrName.length() - 1)) {
            String extension = pathOrName.substring(dotIndex + 1);
            if (SAFE_FILE_EXTENSION.matcher(extension).matches()) {
                builder.put(key, extension);
            }
        }
    }

    private static void enrichChannelType(@NonNull DiagnosticMetadataBuilder builder,
                                          @Nullable String stackHintText) {
        if (!StringUtils.hasText(stackHintText)) {
            return;
        }

        String hintLowerCase = stackHintText.toLowerCase(Locale.ROOT);
        if (hintLowerCase.contains("asynchronoussocketchannel")) {
            builder.put("channelType", "ASYNC_SOCKET_CHANNEL");
        } else if (hintLowerCase.contains("asynchronousfilechannel")) {
            builder.put("channelType", "ASYNC_FILE_CHANNEL");
        } else if (hintLowerCase.contains("datagramchannel")) {
            builder.put("channelType", "DATAGRAM_CHANNEL");
        } else if (hintLowerCase.contains("socketchannel")) {
            builder.put("channelType", "SOCKET_CHANNEL");
        } else if (hintLowerCase.contains("filechannel")) {
            builder.put("channelType", "FILE");
        } else if (hintLowerCase.contains("pipe")) {
            builder.put("channelType", "PIPE");
        } else if (hintLowerCase.contains("socket")) {
            builder.put("channelType", "SOCKET");
        }
    }

    private static boolean isRetryable(@NonNull IoErrorType type, @NonNull IOException cause) {
        return switch (type) {
            case SOCKET_TIMEOUT, CHANNEL_CLOSED, INTERRUPTED -> true;

            case FILESYSTEM_ERROR -> isRetryableFileSystemFailure(cause);

            case FILE_NOT_FOUND, ACCESS_DENIED, FILE_ALREADY_EXISTS, DIRECTORY_NOT_EMPTY,
                 CONTENT_TRUNCATED, CONTENT_CORRUPTED, INVALID_ENCODING, CONFIG_ERROR -> false;

            case GENERIC -> isTransientIoException(cause);
        };
    }

    private static boolean isRetryableFileSystemFailure(@NonNull IOException cause) {
        if (cause instanceof NotDirectoryException
                || cause instanceof NotLinkException
                || cause instanceof FileSystemLoopException) {
            return false;
        }

        if (cause instanceof FileSystemException fileSystemException) {
            return isRetryableFileSystemOsReason(fileSystemException.getReason());
        }

        return true;
    }

    private static boolean isRetryableFileSystemOsReason(@Nullable String reason) {
        if (!StringUtils.hasText(reason)) {
            return true;
        }

        String lower = reason.toLowerCase(Locale.ROOT);
        return NON_RETRYABLE_FS_REASONS.stream().noneMatch(lower::contains);
    }

    /**
     * <p>Алгоритм:
     * <ol>
     *   <li>Проверка сообщения на non-transient маркеры (no space / read-only / too many open files) —
     *       non-transient имеет приоритет над transient, иначе смешанное сообщение
     *       {@code "no space left, broken pipe"} ошибочно классифицируется как retryable.</li>
     *   <li>Проверка типа/сообщения {@code cause} на transient-маркеры.</li>
     *   <li>Рекурсивный спуск по cause-chain (через
     *       {@link CauseChainNavigator#findDeepestCause(Throwable, int)} с лимитом
     *       {@link CauseMetadataEnricher#DIAGNOSTIC_CAUSE_CHAIN_DEPTH}): {@link SocketException} и
     *       {@link SocketTimeoutException} на любой глубине (обёртка в кастомный {@link IOException}
     *       сторонних библиотек).</li>
     * </ol>
     */
    private static boolean isTransientIoException(@NonNull IOException cause) {
        String msg = cause.getMessage();
        if (hasNonTransientMessage(msg)) {
            return false;
        }

        if (isTransientAtFrame(cause, msg)) {
            return true;
        }

        Throwable deepest = CauseChainNavigator.findDeepestCause(cause, CauseMetadataEnricher.DIAGNOSTIC_CAUSE_CHAIN_DEPTH);
        if (deepest == cause) {
            return false;
        }

        String deepestMessage = deepest.getMessage();
        if (hasNonTransientMessage(deepestMessage)) {
            return false;
        }

        return isTransientAtFrame(deepest, deepestMessage);
    }

    private static boolean hasNonTransientMessage(@Nullable String msg) {
        if (!StringUtils.hasText(msg)) {
            return false;
        }

        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("no space left")
                || lower.contains("read-only file system")
                || lower.contains("too many open files");
    }

    private static boolean isTransientAtFrame(@NonNull Throwable frame, @Nullable String msg) {
        String className = frame.getClass().getName();
        if (frame instanceof SocketException
                || frame instanceof SocketTimeoutException
                || className.startsWith("io.netty.")
                || className.contains("ConnectionReset")) {
            return true;
        }

        if (!StringUtils.hasText(msg)) {
            return false;
        }

        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("connection reset")
                || lower.contains("broken pipe")
                || lower.contains("connection timed out")
                || lower.contains("temporarily unavailable");
    }

    private enum IoErrorType {
        FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested file or resource does not exist."),

        ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access to the file or directory is denied due to insufficient permissions."),

        FILE_ALREADY_EXISTS(HttpStatus.CONFLICT, "A file or directory with the same name already exists."),

        DIRECTORY_NOT_EMPTY(HttpStatus.CONFLICT, "The directory cannot be removed because it still contains files or subdirectories."),

        CONTENT_TRUNCATED(HttpStatus.UNPROCESSABLE_ENTITY, "The file content is incomplete or truncated and cannot be processed."),

        CONTENT_CORRUPTED(HttpStatus.UNPROCESSABLE_ENTITY, "The file content is corrupted and cannot be processed."),

        SOCKET_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "A socket read or connection timed out. The upstream service did not respond in time."),

        FILESYSTEM_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "A file system error occurred. The operation could not be completed."),

        CHANNEL_CLOSED(HttpStatus.SERVICE_UNAVAILABLE, "The I/O channel was closed unexpectedly."),

        INTERRUPTED(HttpStatus.SERVICE_UNAVAILABLE, "The I/O operation was interrupted."),

        INVALID_ENCODING(HttpStatus.BAD_REQUEST, "The file contains characters that cannot be converted using the specified encoding."),

        CONFIG_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "A configuration error occurred. The character encoding is not available."),

        GENERIC(HttpStatus.SERVICE_UNAVAILABLE, "A file system or I/O error occurred. The operation could not be completed.");

        private final HttpStatus httpStatus;
        private final String message;

        IoErrorType(@NonNull HttpStatus httpStatus,
                    @NonNull String message) {
            this.httpStatus = httpStatus;
            this.message = message;
        }

        @NonNull
        public HttpStatus httpStatus() {
            return httpStatus;
        }

        @NonNull
        public String message() {
            return message;
        }
    }
}
