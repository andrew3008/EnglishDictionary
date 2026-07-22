package space.br1440.platform.tracing.core.runtime.versioned;

/**
 * Role marker for immutable snapshots published atomically via {@link VersionedStateHolder}.
 * <p>
 * Not a generic {@code HasVersion} contract: only holder-managed agent policy snapshots may
 * implement this interface (ArchUnit allowlist). Application code must not depend on this package.
 */
public interface VersionedState {

    long version();
}
