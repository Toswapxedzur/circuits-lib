package com.minecart.client.payload;

/**
 * Receive side: runs after a {@link Payload} has been deserialized (or otherwise constructed).
 *
 * @param <P> the concrete payload type this handler processes; use {@code PayloadHandler<?>} when storing
 *            mixed handlers.
 */
public interface PayloadHandler<P extends Payload> {

    /**
     * Process one decoded payload.
     *
     * @param payload non-null; must match {@code P}
     */
    void handle(P payload);
}
