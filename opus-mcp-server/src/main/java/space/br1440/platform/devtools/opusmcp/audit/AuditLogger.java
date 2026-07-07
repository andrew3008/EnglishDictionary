package space.br1440.platform.devtools.opusmcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.devtools.opusmcp.security.Masking;

/**
 * Safe audit logger. Emits metadata only (see {@link AuditRecord}) to the audit SLF4J logger, which
 * is routed to stderr/file by logback. Never logs task, context, constraints, model output, the API
 * key, or raw provider responses.
 *
 * <p>Content logging is intentionally not supported in Phase 3. The {@code includeContent} flag is
 * accepted for forward compatibility but, if enabled, only emits a one-time notice that content
 * logging is unsupported.
 */
public final class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("opus.audit");

    private final boolean includeContent;
    private boolean unsupportedNoticeEmitted;

    public AuditLogger() {
        this(false);
    }

    public AuditLogger(boolean includeContent) {
        this.includeContent = includeContent;
    }

    public void log(AuditRecord record) {
        if (record == null) {
            return;
        }
        if (includeContent && !unsupportedNoticeEmitted) {
            unsupportedNoticeEmitted = true;
            log.warn("OPUS_AUDIT_INCLUDE_CONTENT is enabled but content audit logging is not "
                    + "supported in Phase 3; logging metadata only");
        }
        // Defense-in-depth: pass the rendered metadata through masking even though it should
        // never contain secrets by construction.
        log.info("audit {}", Masking.mask(record.toLogString()));
    }
}
