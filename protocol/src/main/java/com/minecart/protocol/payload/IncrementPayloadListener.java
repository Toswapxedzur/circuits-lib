package com.minecart.protocol.payload;

/**
 * Send-side hook for <strong>continuous, incremental</strong> streams: repeated small updates (e.g. batched circuit element
 * deltas flushed each tick). Pair with {@link PayloadHandler} on the receiver.
 * <p>
 * Not for one-shot or snapshot-style payloads (full state sync, single actions, lifecycle handshakes); those are
 * encoded and sent directly without implementing this interface.
 *
 * @param <T> payload type this producer emits
 */
public interface IncrementPayloadListener<T extends Payload> {

    /**
     * Produce the next payload to enqueue for serialization and send, if any. Implementations that batch elsewhere
     * (e.g. {@code sync(Consumer)}) may always return {@code null}.
     *
     * @return payload to send, or {@code null} if nothing is pending
     */
    default T nextPayload() {
        return null;
    }
}
