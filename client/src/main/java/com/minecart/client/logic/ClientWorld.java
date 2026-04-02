package com.minecart.client.logic;

import com.minecart.logic.Circuit;
import com.minecart.logic.World;

import java.util.UUID;

/**
 * Client-side view of one electrical network: holds {@link ClientCircuit}s and links to {@link ClientLevel}.
 * Future: apply topology/variable updates from the server; packet decoding will wire into this type later.
 */
public class ClientWorld extends World {

    public ClientWorld(ClientLevel level) {
        super(level);
    }

    /**
     * Client world with a fixed id (e.g. matching a server {@link com.minecart.logic.World#getId()} for snapshots).
     */
    public ClientWorld(ClientLevel level, UUID worldId) {
        super(level, worldId);
    }

    @Override
    public ClientLevel getLevel() {
        return (ClientLevel) level;
    }

    @Override
    public void addCircuit(Circuit circuit) {
        super.addCircuit(circuit);
        if (circuit instanceof ClientCircuit cc) {
            cc.setWorld(this);
        }
    }

    /**
     * Per-frame client update for all circuits in this world.
     */
    public void tick() {
        for (Circuit c : getCircuits()) {
            if (c instanceof ClientCircuit cc) {
                cc.clientTick();
            }
        }
    }
}
