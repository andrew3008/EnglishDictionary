package space.br1440.platform.tracing.perftests.sut;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.management.Attribute;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Perf-admin мост к JMX-контракту платформы (Фаза 17, PR-4; ТОЛЬКО для перф-стенда).
 * <p>
 * Назначение: дать раннеру сценариев (PowerShell, remote Docker) HTTP-доступ к runtime-операциям
 * extension'а без remote-JMX (M10 reload под нагрузкой, queue-saturation evidence).
 * Контроллер обращается к доменным MBean-ам через in-process {@link MBeanServer} — единственная
 * стандартная точка обмена между application-CL и agent-CL.
 * <p>
 * SUT никогда не публикуется и не является образцом для интеграторов: в production
 * управление — через JMX/SRE-инструменты, не через HTTP.
 */
@RestController
public class PerfAdminController {

    private static final String SAMPLING_OBJECT_NAME =
            "space.br1440.platform.tracing:type=SamplingControl,name=PlatformSamplingControl";
    private static final String SCRUBBING_OBJECT_NAME =
            "space.br1440.platform.tracing:type=ScrubbingControl,name=PlatformScrubbingControl";
    private static final String EXPORT_OBJECT_NAME =
            "space.br1440.platform.tracing:type=ExportControl,name=PlatformExportControl";
    private static final String DIAGNOSTICS_OBJECT_NAME =
            "space.br1440.platform.tracing:type=DiagnosticsControl,name=PlatformDiagnosticsControl";

    private final MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();

    /** M10: смена head-sampling ratio на лету (инвариант P5 — без блокировки hot-path). */
    @PostMapping("/perf/admin/sampling-ratio")
    public ResponseEntity<String> setSamplingRatio(@RequestParam("value") double value) {
        return invoke(() -> {
            mbeanServer.setAttribute(samplingObjectName(), new Attribute("SamplingRatio", value));
            return "ratio=" + value;
        });
    }

    /** M10: runtime-переключение scrubbing'а (домен scrubbing, один апдейт). */
    @PostMapping("/perf/admin/scrubbing")
    public ResponseEntity<String> setScrubbing(@RequestParam("enabled") boolean enabled) {
        return invoke(() -> {
            mbeanServer.invoke(scrubbingObjectName(), "updateScrubbingPolicy",
                    new Object[]{enabled, null},
                    new String[]{"boolean", "[Ljava.lang.String;"});
            return "scrubbing=" + enabled;
        });
    }

    /** M10: export-gate kill-switch. */
    @PostMapping("/perf/admin/export")
    public ResponseEntity<String> setExport(@RequestParam("enabled") boolean enabled) {
        return invoke(() -> {
            mbeanServer.invoke(exportObjectName(), "setExportEnabled",
                    new Object[]{enabled}, new String[]{"boolean"});
            return "export=" + enabled;
        });
    }

    /**
     * M10c/M10d (config storm): снимок состояния sampling-конфигурации — фактический ratio,
     * версия снимка и счётчик отвергнутых апдейтов. Раннер пишет его в reload-storm.csv:
     * valid-storm доказывает монотонный рост версии, invalid-storm — что LKG держится
     * (ratio/version неизменны, InvalidConfigCount растёт). ADR-runtime-sampling-policy C-5/C-6.
     */
    @GetMapping("/perf/admin/sampling-state")
    public ResponseEntity<Map<String, Object>> samplingState() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("samplingRatio", mbeanServer.getAttribute(samplingObjectName(), "SamplingRatio"));
            result.put("configVersion", mbeanServer.getAttribute(samplingObjectName(), "SamplingConfigVersion"));
            result.put("invalidConfigCount", mbeanServer.getAttribute(diagnosticsObjectName(), "InvalidConfigCount"));
            return ResponseEntity.ok(result);
        } catch (InstanceNotFoundException e) {
            return ResponseEntity.ok(Map.of("extensionRegistered", false));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Queue-saturation/degraded evidence: снимок export-метрик (queue size/capacity,
     * drops по причинам, failures/timeouts). Сэмплируется раннером в csv.
     */
    @GetMapping("/perf/admin/export-metrics")
    public ResponseEntity<Map<String, Object>> exportMetrics() {
        try {
            ObjectName exportName = exportObjectName();
            ObjectName diagnosticsName = diagnosticsObjectName();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("queueSize", mbeanServer.getAttribute(exportName, "ExportQueueSize"));
            result.put("queueCapacity", mbeanServer.getAttribute(exportName, "ExportQueueCapacity"));
            result.put("droppedOverflow", mbeanServer.getAttribute(exportName, "ExportDroppedOverflowTotal"));
            result.put("droppedAfterShutdown", mbeanServer.getAttribute(exportName, "ExportDroppedAfterShutdownTotal"));
            result.put("exportFailures", mbeanServer.getAttribute(exportName, "ExportFailuresTotal"));
            result.put("exportTimeouts", mbeanServer.getAttribute(exportName, "ExportTimeoutsTotal"));
            result.put("safeExporter", mbeanServer.getAttribute(exportName, "SafeExporterMetrics"));
            result.put("configReload", mbeanServer.getAttribute(diagnosticsName, "ConfigReloadMetrics"));
            return ResponseEntity.ok(result);
        } catch (InstanceNotFoundException e) {
            // M0/M1: extension не загружен — метрик нет, это валидное состояние стенда.
            return ResponseEntity.ok(Map.of("extensionRegistered", false));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static ObjectName samplingObjectName() throws Exception {
        return new ObjectName(SAMPLING_OBJECT_NAME);
    }

    private static ObjectName scrubbingObjectName() throws Exception {
        return new ObjectName(SCRUBBING_OBJECT_NAME);
    }

    private static ObjectName exportObjectName() throws Exception {
        return new ObjectName(EXPORT_OBJECT_NAME);
    }

    private static ObjectName diagnosticsObjectName() throws Exception {
        return new ObjectName(DIAGNOSTICS_OBJECT_NAME);
    }

    private ResponseEntity<String> invoke(JmxAction action) {
        try {
            return ResponseEntity.ok(action.run());
        } catch (InstanceNotFoundException e) {
            return ResponseEntity.status(409)
                    .body("Доменный MBean не зарегистрирован (сценарий без extension'а?)");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(String.valueOf(e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface JmxAction {
        String run() throws Exception;
    }
}
