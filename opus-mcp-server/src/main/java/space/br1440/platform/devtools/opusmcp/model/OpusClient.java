package space.br1440.platform.devtools.opusmcp.model;

public interface OpusClient {
    OpusResponse generate(OpusRequest request) throws OpusClientException;
}
