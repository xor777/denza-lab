package dev.denza.adaslink;

import java.util.Map;

/**
 * Where the feed's messages go: {@code SomeIpServer.startSomeIpService} and {@code sendSomeIpEvent}.
 * {@link DryRunRecorder} records them; {@link SomeIpPublisher} would fire them and is hard-off.
 */
public interface Publisher {
    BridgeStatus.Mode mode();

    /** startSomeIpService(serviceTopic); the service's result, 0 when started. */
    int startService(long serviceTopic);

    /** sendSomeIpEvent(topic, payload); the service's result, 0 when sent. */
    int fire(long topic, byte[] payload, long monotonicMs);

    /** An input or state change worth keeping beside the fires (trace only; never sent). */
    void note(String kind, Map<String, Object> fields, long monotonicMs);
}
