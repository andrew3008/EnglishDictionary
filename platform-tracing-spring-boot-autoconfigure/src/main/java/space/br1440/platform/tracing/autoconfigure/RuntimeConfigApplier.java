package space.br1440.platform.tracing.autoconfigure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.autoconfigure.jmx.ConfigApplyResult;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.jmx.TracingControlDomain;
import space.br1440.platform.tracing.autoconfigure.sampling.SamplingRuntimeConfig;
import space.br1440.platform.tracing.autoconfigure.sampling.ScrubbingRuntimeConfig;
import space.br1440.platform.tracing.autoconfigure.sampling.ValidationRuntimeConfig;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * <b>Unified runtime policy applier (PR-6E/7C/8C).</b> Публикует все изменяемые домены из
 * {@link TracingProperties} через {@link PlatformTracingJmxClient}.
 * Один домен = один атомарный JMX-вызов; agent-side holder'ы — источник истины.
 *
 * <p><b>Fail-closed availability gate.</b> Если хотя бы один из шести доменных MBean
 * не зарегистрирован ({@link PlatformTracingJmxClient#allMBeansAvailable()}), push полностью
 * пропускается с логом {@code [REJECTED]}.
 *
 * <p><b>Best-effort домен-изоляция.</b> Каждый домен изолирован try/catch; отказ одного не
 * мешает остальным. При сбое хотя бы одного домена — лог {@code [PARTIAL applied=N failed=M]}.
 *
 * <p><b>Spring-side диагностика.</b> Хранит {@link ConfigApplyResult} последнего вызова,
 * счётчики отклонений и частичных сбоев. Метрики фиксируются через
 * {@code platform.tracing.config.apply.result{domain,result}}.
 */
public class RuntimeConfigApplier {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigApplier.class);

    private static final String METRIC_CONFIG_APPLY_RESULT = "platform.tracing.config.apply.result";
    private static final String TAG_DOMAIN = "domain";
    private static final String TAG_RESULT = "result";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    private final PlatformTracingJmxClient client;
    private final MeterRegistry meterRegistry;

    private final LongAdder rejectedApplyCount = new LongAdder();
    private final LongAdder partialApplyCount = new LongAdder();
    private volatile ConfigApplyResult lastConfigApplyResult;

    public RuntimeConfigApplier(PlatformTracingJmxClient client) {
        this(client, null);
    }

    public RuntimeConfigApplier(PlatformTracingJmxClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Публикует все изменяемые домены из текущего снимка свойств.
     * No-op с логом {@code [REJECTED]}, если хотя бы один MBean недоступен.
     * При сбое отдельного домена — продолжает остальные, фиксирует {@code [PARTIAL]}.
     */
    public void applyAll(TracingProperties properties) {
        if (!client.allMBeansAvailable()) {
            rejectedApplyCount.increment();
            log.warn("RuntimeConfigApplier [REJECTED]: не все доменные MBean доступны — push пропущен");
            return;
        }

        Set<TracingControlDomain> applied = EnumSet.noneOf(TracingControlDomain.class);
        Set<TracingControlDomain> failed = EnumSet.noneOf(TracingControlDomain.class);

        runDomain(TracingControlDomain.SAMPLING, "sampling",
                () -> client.updateSamplingPolicy(SamplingRuntimeConfig.from(properties.getSampling())),
                applied, failed);
        runDomain(TracingControlDomain.SCRUBBING, "scrubbing",
                () -> client.updateScrubbingPolicy(ScrubbingRuntimeConfig.from(properties.getScrubbing())),
                applied, failed);
        runDomain(TracingControlDomain.VALIDATION, "validation",
                () -> client.updateValidationPolicy(ValidationRuntimeConfig.from(properties.getValidation())),
                applied, failed);
        runDomain(TracingControlDomain.EXPORT, "export",
                () -> client.setExportEnabled(properties.getExporter().isEnabled()),
                applied, failed);
        runDomain(TracingControlDomain.DIAGNOSTICS, "propagation",
                () -> client.setPropagationEnabled(properties.getPropagation().isEnabled()),
                applied, failed);

        String level = properties.getDiagnostics().getLogLevel();
        if (level != null && !level.isBlank()) {
            runDomain(TracingControlDomain.DIAGNOSTICS, "log_level",
                    () -> client.setPlatformLogLevel(level),
                    applied, failed);
        }

        lastConfigApplyResult = new ConfigApplyResult(
                Collections.unmodifiableSet(applied),
                Collections.unmodifiableSet(failed),
                Instant.now());

        if (!failed.isEmpty()) {
            partialApplyCount.increment();
            log.warn("RuntimeConfigApplier [PARTIAL applied={} failed={}]: некоторые домены не применены",
                    applied.size(), failed.size());
        }
    }

    // -- Spring-side диагностика ----------------------------------------------------

    /** Количество полных отклонений (gate недоступен, push не выполнялся совсем). */
    public long getRejectedApplyCount() {
        return rejectedApplyCount.sum();
    }

    /** Количество частичных сбоев (gate прошёл, но хотя бы один домен упал). */
    public long getPartialApplyCount() {
        return partialApplyCount.sum();
    }

    /**
     * Результат последнего вызова {@link #applyAll}.
     * {@code null}, если {@link #applyAll} ещё ни разу не прошёл gate.
     */
    public ConfigApplyResult getLastConfigApplyResult() {
        return lastConfigApplyResult;
    }

    // -- Internal -------------------------------------------------------------------

    private void runDomain(TracingControlDomain domain, String label,
                           Runnable push,
                           Set<TracingControlDomain> applied,
                           Set<TracingControlDomain> failed) {
        try {
            push.run();
            applied.add(domain);
            incrementApplyResult(label, RESULT_SUCCESS);
            log.debug("RuntimeConfigApplier [DOMAIN={}]: applied", label);
        } catch (Exception e) {
            failed.add(domain);
            incrementApplyResult(label, RESULT_FAILURE);
            log.warn("RuntimeConfigApplier [DOMAIN={}]: не удалось применить домен: {}", label, e.getMessage());
        }
    }

    private void incrementApplyResult(String domain, String result) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_CONFIG_APPLY_RESULT)
                .tag(TAG_DOMAIN, domain)
                .tag(TAG_RESULT, result)
                .register(meterRegistry)
                .increment();
    }
}
