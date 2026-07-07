package space.br1440.platform.devtools.opusmcp;

import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.devtools.opusmcp.server.McpServerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point for the read-only stdio MCP server (Phase 0A / Phase 1).
 *
 * <p>Lifecycle: build the stdio server, then block the main thread. The process is terminated by
 * the MCP client (Cursor) closing stdin / sending SIGTERM; a shutdown hook closes the server
 * gracefully on stdin EOF or process termination.
 *
 * <p>stdout is reserved strictly for MCP JSON-RPC. All diagnostics go to stderr (see logback.xml).
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        log.info("Starting {} (stdio transport)", McpServerFactory.SERVER_NAME);

        McpSyncServer server = new McpServerFactory().create();

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received; closing MCP server gracefully");
            try {
                server.closeGracefully();
            } catch (RuntimeException e) {
                log.warn("Graceful close failed, forcing close: {}", e.getClass().getSimpleName());
                server.close();
            } finally {
                shutdownLatch.countDown();
            }
        }, "opus-mcp-shutdown"));

        log.info("MCP server ready; awaiting client requests over stdio");
        shutdownLatch.await();
        log.info("MCP server stopped");
    }
}
