package space.br1440.platform.tracing.otel.mdc.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RemoteServiceTraceMirrorTest {

    @Test
    void capacityEvictsOldestEntryAndNeverExceedsBound() {
        var ticker = new AtomicLong();
        try (var mirror = new RemoteServiceTraceMirror(3, Duration.ofMinutes(1), ticker::get)) {
            mirror.put("trace-1", "remote-1");
            mirror.put("trace-2", "remote-2");
            mirror.put("trace-3", "remote-3");
            mirror.put("trace-4", "remote-4");

            assertThat(mirror.size()).isEqualTo(3);
            assertThat(mirror.get("trace-1")).isEmpty();
            assertThat(mirror.get("trace-4")).contains("remote-4");
        }
    }

    @Test
    void ttlExpiresOrphanedEntryWithoutBackgroundWorker() {
        var ticker = new AtomicLong();
        try (var mirror = new RemoteServiceTraceMirror(3, Duration.ofSeconds(30), ticker::get)) {
            mirror.put("trace-1", "remote-1");
            ticker.addAndGet(Duration.ofSeconds(30).toNanos());

            assertThat(mirror.get("trace-1")).isEmpty();
            assertThat(mirror.size()).isZero();
        }
    }

    @Test
    void updatedEntryMovesToNewestTtlPosition() {
        var ticker = new AtomicLong();
        try (var mirror = new RemoteServiceTraceMirror(3, Duration.ofSeconds(30), ticker::get)) {
            mirror.put("trace-1", "remote-old");
            mirror.put("trace-2", "remote-2");
            ticker.addAndGet(Duration.ofSeconds(20).toNanos());
            mirror.put("trace-1", "remote-new");
            ticker.addAndGet(Duration.ofSeconds(15).toNanos());

            assertThat(mirror.get("trace-2")).isEmpty();
            assertThat(mirror.get("trace-1")).contains("remote-new");
            assertThat(mirror.size()).isEqualTo(1);
        }
    }

    @Test
    void concurrentWritesRespectStrictCardinalityBound() throws Exception {
        int maxEntries = 128;
        try (var mirror = new RemoteServiceTraceMirror(maxEntries, Duration.ofMinutes(1), System::nanoTime);
             var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int index = 0; index < 2_048; index++) {
                int entry = index;
                tasks.add(() -> {
                    mirror.put("trace-" + entry, "remote-" + entry);
                    return null;
                });
            }

            for (var result : executor.invokeAll(tasks)) {
                result.get();
            }

            assertThat(mirror.size()).isEqualTo(maxEntries);
        }
    }

    @Test
    void closeClearsStateAndRejectsFurtherWrites() {
        var mirror = new RemoteServiceTraceMirror(3, Duration.ofMinutes(1), System::nanoTime);
        mirror.put("trace-1", "remote-1");

        mirror.close();
        mirror.put("trace-2", "remote-2");

        assertThat(mirror.size()).isZero();
        assertThat(mirror.get("trace-1")).isEmpty();
        assertThat(mirror.get("trace-2")).isEmpty();
    }

    @Test
    void invalidBoundsAreRejected() {
        assertThatThrownBy(() -> new RemoteServiceTraceMirror(0, Duration.ofMinutes(1), System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteServiceTraceMirror(1, Duration.ZERO, System::nanoTime))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
