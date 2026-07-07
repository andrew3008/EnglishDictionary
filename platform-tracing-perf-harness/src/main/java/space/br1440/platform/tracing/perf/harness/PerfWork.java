package space.br1440.platform.tracing.perf.harness;

final class PerfWork {

    private PerfWork() {
    }

    static long deterministicChecksum(long seed, int iterations) {
        long checksum = seed;
        for (int i = 0; i < iterations; i++) {
            checksum = checksum * 31 + (seed ^ i);
        }
        return checksum;
    }
}
