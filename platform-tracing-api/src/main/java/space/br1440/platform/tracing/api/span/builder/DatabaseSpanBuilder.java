package space.br1440.platform.tracing.api.span.builder;

import io.opentelemetry.api.common.AttributeKey;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.sanitize.SqlSanitizer;

/**
 * Escape-hatch builder DB-span'а (CLIENT). Имя — {@code {operation} {collection}} (НЕ raw SQL).
 * {@code db.statement} санитизируется (литералы -> {@code ?}) и пишется lazy. Использовать ТОЛЬКО
 * для драйверов, не инструментируемых Агентом.
 */
public interface DatabaseSpanBuilder extends PlatformSpanBuilder<DatabaseSpanBuilder> {

    /** {@code db.statement} — incubating semconv-ключ, намеренно не типизирован в SemconvKeys. */
    AttributeKey<String> DB_STATEMENT = AttributeKey.stringKey("db.statement");

    /** {@code db.system.name} (например {@code postgresql}); обязателен хотя бы один из system/legacy. */
    @Nonnull
    default DatabaseSpanBuilder system(@Nonnull String dbSystem) {
        return attribute(SemconvKeys.DB_SYSTEM_NAME, dbSystem);
    }

    /** {@code db.collection.name} — таблица/коллекция. */
    @Nonnull
    default DatabaseSpanBuilder collection(@Nonnull String collection) {
        return attribute(SemconvKeys.DB_COLLECTION_NAME, collection);
    }

    /** {@code db.operation.name} — операция (SELECT/INSERT/...), используется в имени span'а. */
    @Nonnull
    default DatabaseSpanBuilder operation(@Nonnull String operation) {
        return attribute(SemconvKeys.DB_OPERATION_NAME, operation);
    }

    /** Сырой SQL: записывается санитизированным в {@code db.statement} (lazy, под isRecording). */
    @Nonnull
    default DatabaseSpanBuilder statement(@Nonnull String rawSql) {
        return lazyAttribute(DB_STATEMENT, () -> SqlSanitizer.sanitize(rawSql));
    }
}
