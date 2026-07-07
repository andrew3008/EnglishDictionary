package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main-класс для e2e safety-smoke BSP overflow: запускается в дочерней JVM с OTel Java Agent,
 * настроенным на недоступный OTLP endpoint и маленькую BSP-очередь. Цель —
 * убедиться, что приложение остаётся живо при переполнении/недоступности pipeline.
 *
 * <p>Сценарий:</p>
 * <ol>
 *   <li>создать {@code spans} span'ов в цикле — гарантированный overflow;</li>
 *   <li>после генерации проверить, что код продолжает выполняться (probe "alive after spam");</li>
 *   <li>дать паузу {@code postSpamHoldMs} мс, чтобы возможные background-ошибки экспорта
 *       успели сработать без падения приложения;</li>
 *   <li>выйти с exit code 0.</li>
 * </ol>
 *
 * <p>Heap usage специально <b>не</b> валидируется здесь — это soft sanity, который легко
 * становится flaky в subprocess/CI. Эксплуатационный success = приложение не упало,
 * exit code 0, probe "alive" сработал.</p>
 *
 * <p>Аргументы: {@code spans postSpamHoldMs}</p>
 */
public final class BspOverflowSafetyMain {

    private BspOverflowSafetyMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Expected args: spans postSpamHoldMs");
        }
        int spans = Integer.parseInt(args[0]);
        long postSpamHoldMs = Long.parseLong(args[1]);

        // GlobalOpenTelemetry уже инициализирован Agent'ом в premain (с auto-configure).
        Tracer tracer = GlobalOpenTelemetry.getTracer("bsp-overflow-safety");

        AtomicBoolean alive = new AtomicBoolean(false);

        // Спам-фаза: гарантируем переполнение очереди при недоступном OTLP endpoint.
        for (int i = 0; i < spans; i++) {
            Span span = tracer.spanBuilder("bsp.overflow." + i).startSpan();
            span.setAttribute("probe.seq", i);
            span.end();
        }

        // Probe "alive": если мы досюда дошли — приложение не зависло и не упало.
        alive.set(true);
        System.out.println("ALIVE_AFTER_SPAM=true spans=" + spans);

        // Удерживающая пауза: даём background-export retry'ям возможность сработать и убедиться,
        // что они не валят процесс.
        Thread.sleep(postSpamHoldMs);
        System.out.println("ALIVE_AFTER_HOLD=" + alive.get() + " hold_ms=" + postSpamHoldMs);

        // Намеренно НЕ форсируем shutdown — Agent сам отработает shutdown hook.
        // exit code 0 — главный gate теста.
    }
}
