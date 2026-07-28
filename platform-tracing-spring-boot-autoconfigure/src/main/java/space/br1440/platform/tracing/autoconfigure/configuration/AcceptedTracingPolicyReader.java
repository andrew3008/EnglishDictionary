package space.br1440.platform.tracing.autoconfigure.configuration;

/**
 * Application-side порт чтения authoritative accepted policy без mutation capability.
 */
interface AcceptedTracingPolicyReader {

    AcceptedTracingPolicyView current();
}
