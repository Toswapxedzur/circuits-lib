package com.minecart.client.network;

import com.minecart.protocol.payload.Payload;
import com.minecart.serialization.tag.CompoundTag;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPayloadDispatcherTest {

    /** Minimal client-bound payload for driving the dispatcher without the codec. */
    private static final class FakeClientPayload implements Payload {
        @Override
        public String getPayloadId() {
            return "test.fake_client_payload";
        }

        @Override
        public Destination getDestination() {
            return Destination.CLIENT;
        }

        @Override
        public void save(CompoundTag tag) {
            // not exercised in this test
        }
    }

    /**
     * M8: a handler that throws must not silently escape (it used to bubble through the UI executor
     * and never reach the pipeline). It should close the connection instead.
     */
    @Test
    void handlerException_closesChannel() {
        ClientPayloadDispatcher dispatcher = new ClientPayloadDispatcher(Runnable::run);
        dispatcher.register(FakeClientPayload.class, p -> {
            throw new IllegalStateException("boom");
        });
        EmbeddedChannel channel = new EmbeddedChannel(dispatcher);

        channel.writeInbound(new FakeClientPayload());

        assertFalse(channel.isOpen(), "a throwing handler must close the connection");
    }

    /** A server-bound payload arriving on the client is a protocol violation and must close the channel. */
    @Test
    void serverBoundPayload_closesChannel() {
        ClientPayloadDispatcher dispatcher = new ClientPayloadDispatcher(Runnable::run);
        EmbeddedChannel channel = new EmbeddedChannel(dispatcher);

        channel.writeInbound(new Payload() {
            @Override
            public String getPayloadId() {
                return "test.server_bound";
            }

            @Override
            public Destination getDestination() {
                return Destination.SERVER;
            }

            @Override
            public void save(CompoundTag tag) {
            }
        });

        assertFalse(channel.isOpen(), "server-bound payload on the client must close the connection");
    }

    /** A normal handler dispatch leaves the channel open. */
    @Test
    void normalDispatch_keepsChannelOpen() {
        boolean[] handled = {false};
        ClientPayloadDispatcher dispatcher = new ClientPayloadDispatcher(Runnable::run);
        dispatcher.register(FakeClientPayload.class, p -> handled[0] = true);
        EmbeddedChannel channel = new EmbeddedChannel(dispatcher);

        channel.writeInbound(new FakeClientPayload());

        assertTrue(handled[0], "handler should have run");
        assertTrue(channel.isOpen(), "a successful dispatch must not close the connection");
    }
}
