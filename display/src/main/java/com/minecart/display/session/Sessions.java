package com.minecart.display.session;

import com.badlogic.gdx.Gdx;
import com.minecart.client.handler.CircuitElementHandler;
import com.minecart.client.handler.CircuitLifecycleHandler;
import com.minecart.client.handler.CircuitSnapshotHandler;
import com.minecart.client.handler.WorldLifecycleHandler;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.network.ClientConnection;
import com.minecart.client.network.ClientPayloadDispatcher;
import com.minecart.event.info.InfoInjectors;
import com.minecart.display.world.WorldEntry;
import com.minecart.registry.AllElementInfos;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
import com.minecart.protocol.payload.server.CircuitSnapshotPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.server.integrated.IntegratedServer;

/**
 * Builds client+server sessions in two flavors:
 * <ul>
 *   <li>{@link #singleplayer()} — boots an {@link IntegratedServer} (in-process LocalChannel, separate tick thread)
 *       and connects a fresh {@link ClientConnection} to it via {@link ClientConnection#connectIntegrated}.</li>
 *   <li>{@link #multiplayer(String, int)} — connects a fresh {@link ClientConnection} to a remote dedicated server
 *       via {@link ClientConnection#connectRemote}; no integrated server is started.</li>
 * </ul>
 * Same client-side pipeline (decoder + encoder + dispatcher) and same set of {@link com.minecart.protocol.payload.PayloadHandler}s
 * either way, so multiplayer code is exercised in singleplayer too.
 */
public final class Sessions {

    private Sessions() {}

    /**
     * Boots a transient (in-memory) integrated server and connects to it. Throws if either step fails. Does not
     * persist anything; useful for tests / quick experiments. Most singleplayer flows want
     * {@link #singleplayer(WorldEntry)} instead so {@code level.dat} loads/saves work.
     */
    public static Session singleplayer() throws Exception {
        return startIntegrated(new IntegratedServer());
    }

    /**
     * Boots an integrated server backed by {@code world}'s directory: loads {@code level.dat} on start, and the
     * returned {@link Session#integrated()} can be {@code save()}-ed at any time. Throws if loading or connecting
     * fails (the partial integrated server is already torn down).
     */
    public static Session singleplayer(WorldEntry world) throws Exception {
        return startIntegrated(new IntegratedServer(world.dir().file().toPath()));
    }

    private static Session startIntegrated(IntegratedServer integrated) throws Exception {
        integrated.start();
        try {
            ClientLevel level = newClientLevel();
            ClientConnection connection = newConnection(level);
            try {
                connection.connectIntegrated(integrated.address());
            } catch (Throwable t) {
                connection.close();
                throw t;
            }
            return new Session(level, connection, integrated);
        } catch (Throwable t) {
            integrated.stop();
            throw t;
        }
    }

    /**
     * Connects to an already-running remote dedicated server.
     */
    public static Session multiplayer(String host, int port) throws Exception {
        ClientLevel level = newClientLevel();
        ClientConnection connection = newConnection(level);
        try {
            connection.connectRemote(host, port);
        } catch (Throwable t) {
            connection.close();
            throw t;
        }
        return new Session(level, connection, null);
    }

    private static ClientLevel newClientLevel() {
        ClientLevel level = new ClientLevel();
        // Touch AllElementInfos so its types are registered before any payload is decoded.
        Class<?> ignored = AllElementInfos.class;
        // Inject default PositionInfo / RotationInfo on the client mirror; loaded payloads override these.
        InfoInjectors.attach(level);
        return level;
    }

    private static ClientConnection newConnection(ClientLevel level) {
        ClientPayloadDispatcher dispatcher = new ClientPayloadDispatcher(Gdx.app::postRunnable)
                .register(CircuitElementPayload.class, new CircuitElementHandler(level))
                .register(CircuitSnapshotPayload.class, new CircuitSnapshotHandler(level))
                .register(CircuitLifecyclePayload.class, new CircuitLifecycleHandler(level))
                .register(WorldLifecyclePayload.class, new WorldLifecycleHandler(level));
        return new ClientConnection(dispatcher);
    }

    /**
     * Bundle of the per-session objects {@link com.minecart.display.screen.GameScreen} owns.
     * {@code integrated} is {@code null} for multiplayer sessions.
     */
    public record Session(ClientLevel level, ClientConnection connection, IntegratedServer integrated) {
    }
}
