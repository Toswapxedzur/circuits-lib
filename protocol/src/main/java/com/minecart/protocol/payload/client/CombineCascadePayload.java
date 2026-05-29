package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Client → server: request an atomic combine-cascade on one or more node pairs. Each pair is
 * {@code (survivorId, absorbedId)} — the same direction policy applies as
 * {@link com.minecart.logic.ServerWorld#combineNodes}: when one side is a port of its component
 * the port wins regardless of how the client passed the arguments. The server replays the list
 * through {@code CombineCascadeEngine}; either every pair lands or none do.
 *
 * <h2>Gesture id</h2>
 * The optional {@link #getGestureId()} matches the {@code dragGestureId} of the drag that emitted
 * this combine, when the combine arose from a drop. Servers may use it to correlate the merge
 * with this-tick streaming Move payloads (same gesture id) for diagnostics; it's no longer used
 * for any lease / lock decision since the drag-lease system was removed. Combines not initiated
 * from a drag (panel edits, scripted edits) leave it {@code null}.
 *
 * <p>Replaces the per-step trio of {@link ReplaceComponentNodePayload} +
 * {@link EdgeEndpointChangePayload} + {@link DeleteElementPayload} that the editor currently sends
 * for a single merge. The granular payloads remain for non-cascade-aware paths (e.g. legacy save
 * migration); the new cascade payload is preferred for any user-initiated merge.
 */
public final class CombineCascadePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_COMBINE_CASCADE;

    public static final PayloadType<CombineCascadePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CombineCascadePayload::new);

    private UUID worldId;
    private UUID gestureId;
    private final List<CombinePair> pairs = new ArrayList<>();

    public CombineCascadePayload() {
    }

    public CombineCascadePayload(UUID worldId, UUID gestureId, List<CombinePair> pairs) {
        this.worldId = worldId;
        this.gestureId = gestureId;
        if (pairs != null) {
            this.pairs.addAll(pairs);
        }
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.SERVER;
    }

    public UUID getWorldId() {
        return worldId;
    }

    public UUID getGestureId() {
        return gestureId;
    }

    public List<CombinePair> getPairs() {
        return Collections.unmodifiableList(pairs);
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        if (gestureId != null) {
            TagUtil.putUUID(tag, ProtocolStrings.TAG_GESTURE_ID, gestureId);
        }
        ListTag list = new ListTag();
        for (CombinePair p : pairs) {
            CompoundTag step = new CompoundTag();
            p.save(step);
            list.add(step);
        }
        tag.put(ProtocolStrings.TAG_COMBINE_PAIRS, list);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        // Gesture id is optional — absent for panel-driven combines.
        gestureId = TagUtil.getUUID(tag, ProtocolStrings.TAG_GESTURE_ID);
        pairs.clear();
        Tag t = tag.get(ProtocolStrings.TAG_COMBINE_PAIRS);
        if (t instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                CompoundTag step = TagUtil.requireCompoundTag(
                        list.get(i), ProtocolStrings.TAG_COMBINE_PAIRS + "[" + i + "]");
                pairs.add(CombinePair.load(step));
            }
        }
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException(
                    "CombineCascadePayload must carry at least one '" + ProtocolStrings.TAG_COMBINE_PAIRS + "'");
        }
    }

    /**
     * A single merge request. The server reads {@code survivorId} and {@code absorbedId} as
     * candidate roles; the cascade engine reserves the right to swap them per its direction
     * policy before applying.
     */
    public static final class CombinePair {
        private final UUID survivorId;
        private final UUID absorbedId;

        public CombinePair(UUID survivorId, UUID absorbedId) {
            this.survivorId = survivorId;
            this.absorbedId = absorbedId;
        }

        public UUID getSurvivorId() {
            return survivorId;
        }

        public UUID getAbsorbedId() {
            return absorbedId;
        }

        void save(CompoundTag tag) {
            TagUtil.putUUID(tag, ProtocolStrings.TAG_SURVIVOR_NODE_ID, survivorId);
            TagUtil.putUUID(tag, ProtocolStrings.TAG_ABSORBED_NODE_ID, absorbedId);
        }

        static CombinePair load(CompoundTag tag) {
            UUID s = TagUtil.getUUID(tag, ProtocolStrings.TAG_SURVIVOR_NODE_ID);
            UUID a = TagUtil.getUUID(tag, ProtocolStrings.TAG_ABSORBED_NODE_ID);
            if (s == null) {
                throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_SURVIVOR_NODE_ID + "'");
            }
            if (a == null) {
                throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_ABSORBED_NODE_ID + "'");
            }
            return new CombinePair(s, a);
        }
    }
}
