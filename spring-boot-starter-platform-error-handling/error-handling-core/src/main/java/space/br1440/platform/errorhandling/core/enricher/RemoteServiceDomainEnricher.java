package space.br1440.platform.errorhandling.core.enricher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.extension.ErrorEntryEnricher;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Enricher, заменяющий категориальное значение {@code domain} cause-деталей
 * (например, {@code "http-integration"}, {@code "kafka"}, {@code "timeout"})
 * на реальное имя upstream-сервиса, предоставленное Tracing-стартером.
 *
 * <h2>Источник имени upstream'а</h2>
 * <p>Имя приходит через {@code Supplier<Optional<String>>}, поставляемый
 * {@code platform-tracing-spring-boot-autoconfigure} ({@code platformRemoteServiceNameProvider}).
 * Tracing-стартер записывает имя в MDC-ключ {@code platform.remote.service} в момент
 * закрытия CLIENT-span'а с ERROR-статусом и поддерживает sticky-семантику этого ключа,
 * чтобы значение было доступно на этапе обработки исключения. Контракт supplier'а —
 * стандартный {@link Supplier}; жёсткой связи с Tracing-стартером на этапе компиляции нет.
 *
 * <h2>preserve-upstream domain</h2>
 * <p>Enricher переписывает {@code domain} только у тех {@link ErrorInfoDetail},
 * у которых текущий {@code domain} входит в whitelist
 * {@link space.br1440.platform.errorhandling.properties.ErrorHandlingProperties.Tracing#getIntegrationDomains()}.
 * Это гарантирует:
 * <ul>
 *   <li>детали, у которых {@code domain} — это уже распознанное имя upstream-сервиса
 *       (например, {@code "order-service"} от {@code UpstreamErrorBodyParser}), сохраняются
 *       как есть — апстрим остаётся источником правды о собственном имени;</li>
 *   <li>детали с не-интеграционным {@code domain} (например, {@code "validation"},
 *       {@code "oauth2"}, {@code "persistence"}) не трогаются — это локальные категории
 *       ошибок, не относящиеся к вызовам внешних сервисов.</li>
 * </ul>
 *
 * <h2>Порядок в pipeline</h2>
 * <p>Cause-стратегии формируют {@link ErrorInfoDetail} ВНУТРИ {@code top-strategy.handle()},
 * который завершается до того, как pipeline перебирает enricher'ы — то есть «после
 * cause-strategy» получается автоматически.
 *
 * <p>Среди enricher'ов важен порядок относительно других, способных выставить
 * {@code domain}: на сегодня единственный другой платформенный enricher —
 * {@code HelpDetailEnricher} с {@link Ordered#LOWEST_PRECEDENCE} (он {@code domain}
 * не трогает), поэтому {@link #getOrder()} возвращает {@link Ordered#HIGHEST_PRECEDENCE}
 * {@code + 200} с большим запасом, чтобы оставить интервал для возможных будущих
 * domain-enricher'ов между ним и пользовательскими.
 *
 * <h2>Защита от багов tracing-стартера</h2>
 * <p>Вызов {@link Supplier#get()} обёрнут в {@code try/catch}: формально supplier
 * декларирует never-throw, но если он по какой-то причине бросит исключение,
 * pipeline error-handling не должен падать — enricher просто вернёт {@link ErrorEntry}
 * без изменений и залогирует событие на уровне DEBUG.
 */
@Slf4j
public final class RemoteServiceDomainEnricher implements ErrorEntryEnricher {

    private final Supplier<Optional<String>> remoteServiceNameProvider;
    private final Set<String> integrationDomains;

    public RemoteServiceDomainEnricher(@NonNull Supplier<Optional<String>> remoteServiceNameProvider,
                                       @NonNull Set<String> integrationDomains) {
        this.remoteServiceNameProvider = remoteServiceNameProvider;
        // Защитная копия: Set.copyOf уже выполнен на стороне ErrorHandlingProperties.Tracing,
        // но повторяем здесь, чтобы класс был корректным при прямом инстанциировании
        // (не через Spring DI, а в тестах или в кастомных конфигурациях).
        this.integrationDomains = Set.copyOf(integrationDomains);
    }

    @Override
    public int getOrder() {
        // См. Javadoc класса: высокий приоритет среди enricher'ов, чтобы остальные
        // (например, HelpDetailEnricher с LOWEST_PRECEDENCE) работали уже на финальном
        // domain. Запас в 200 единиц оставляет интервал для пользовательских вставок.
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    @NonNull
    public ErrorEntry enrich(@NonNull ErrorEntry entry, @NonNull ErrorHandlingContext context) {
        Optional<String> remoteOpt = readRemoteServiceName();
        if (remoteOpt.isEmpty()) {
            return entry;
        }

        String remote = remoteOpt.get();
        List<ErrorDetail> originalDetails = entry.getDetails();
        if (originalDetails.isEmpty()) {
            return entry;
        }

        boolean changed = false;
        List<ErrorDetail> updated = new ArrayList<>(originalDetails.size());
        for (ErrorDetail detail : originalDetails) {
            if (detail instanceof ErrorInfoDetail info && shouldRewriteDomain(info.getDomain())) {
                updated.add(info.toBuilder().domain(remote).build());
                changed = true;
            } else {
                updated.add(detail);
            }
        }

        if (!changed) {
            return entry;
        }

        // ErrorEntry.details помечен @Singular: метод details(Collection) у Lombok-builder'а
        // ДОБАВЛЯЕТ элементы, а не заменяет. Поэтому очищаем существующий список через
        // clearDetails(), прежде чем подложить обновлённую копию.
        return entry.toBuilder()
                .clearDetails()
                .details(Collections.unmodifiableList(updated))
                .build();
    }

    /**
     * Безопасное чтение имени upstream-сервиса. Никакое исключение из supplier'а не должно
     * остановить pipeline error-handling.
     */
    private Optional<String> readRemoteServiceName() {
        try {
            Optional<String> value = remoteServiceNameProvider.get();
            if (value == null || value.isEmpty()) {
                return Optional.empty();
            }
            String name = value.get();
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(name);
        } catch (RuntimeException ex) {
            log.debug("Remote service name supplier threw an exception; skipping domain enrichment", ex);
            return Optional.empty();
        }
    }

    /**
     * Решение о замене {@code domain}: только если текущее значение — категориальный
     * маркер из whitelist. Все прочие значения (включая {@code null}) оставляем как есть.
     */
    private boolean shouldRewriteDomain(String currentDomain) {
        return currentDomain != null && integrationDomains.contains(currentDomain);
    }
}
