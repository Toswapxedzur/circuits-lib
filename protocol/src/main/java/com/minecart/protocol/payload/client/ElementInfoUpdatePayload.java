package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.BoolTag;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.DoubleTag;
import com.minecart.serialization.tag.IntTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.StringTag;
import com.minecart.serialization.tag.Tag;
import com.minecart.ui.panel.PanelSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client → server: "the user clicked Save on the info panel for element {@code elementId} in
 * {@code worldId}; here are the field values they had at that moment".
 *
 * <p>By design the payload does <strong>not</strong> directly mutate server state. The server
 * handler resolves the element and posts a
 * {@link com.minecart.event.events.ElementInfoUpdateEvent}; element-class listeners pick the
 * fields they care about out of the snapshot and apply them. Invalid values simply get ignored,
 * the server's next sync reasserts the unchanged state, and reopening the panel shows the old
 * data — exactly the validation policy chosen during design.
 *
 * <p>Wire format: a {@link CompoundTag} envelope containing the world id, the element id, and a
 * nested {@link CompoundTag} holding the snapshot's key/value entries. Value types supported on
 * the wire are {@link String}, {@code double}, {@code long}, and {@code boolean} — same set the
 * {@link PanelSnapshot} client side knows how to populate.
 */
public final class ElementInfoUpdatePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_ELEMENT_INFO_UPDATE;

    public static final PayloadType<ElementInfoUpdatePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, ElementInfoUpdatePayload::new);

    private UUID worldId;
    private UUID elementId;
    private PanelSnapshot snapshot;

    public ElementInfoUpdatePayload() {}

    public ElementInfoUpdatePayload(UUID worldId, UUID elementId, PanelSnapshot snapshot) {
        this.worldId = worldId;
        this.elementId = elementId;
        this.snapshot = snapshot;
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.SERVER;
    }

    public UUID getWorldId() { return worldId; }
    public UUID getElementId() { return elementId; }
    public PanelSnapshot getSnapshot() { return snapshot; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
        tag.put(ProtocolStrings.TAG_PANEL_SNAPSHOT, encode(snapshot));
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = Payload.requireUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        elementId = Payload.requireUUID(tag, ProtocolStrings.TAG_ELEMENT_ID);
        Tag snapshotTag = tag.get(ProtocolStrings.TAG_PANEL_SNAPSHOT);
        snapshot = snapshotTag instanceof CompoundTag ct ? decode(ct) : PanelSnapshot.builder().build();
    }

    /**
     * Writes each snapshot value with its natural tag type so the reader can recover the runtime
     * Java type without per-payload metadata. Unsupported types (anything outside Number / Boolean /
     * String) are stringified — a defensive fallback that keeps the wire format from blowing up if
     * a future field kind sends something exotic.
     */
    private static CompoundTag encode(PanelSnapshot snapshot) {
        CompoundTag out = new CompoundTag();
        if (snapshot == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : snapshot.asMap().entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (v instanceof Boolean b) {
                out.putBoolean(key, b);
            } else if (v instanceof Long l) {
                // A 32-bit IntTag would silently truncate a long (PanelSnapshot.Builder.put(String,long)
                // stores Longs, and the class documents long support). Encode the full 64 bits as a
                // two-element ListTag [hi32, lo32] — self-describing on the wire, so the decoder can
                // recover the exact Long without per-field type metadata. Round-trips losslessly.
                out.put(key, encodeLong(l));
            } else if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
                // Values that genuinely fit in 32 bits stay compact IntTags.
                out.putInt(key, ((Number) v).intValue());
            } else if (v instanceof Number n) {
                out.putDouble(key, n.doubleValue());
            } else if (v instanceof String s) {
                out.putString(key, s);
            } else {
                out.putString(key, String.valueOf(v));
            }
        }
        return out;
    }

    private static PanelSnapshot decode(CompoundTag tag) {
        PanelSnapshot.Builder b = PanelSnapshot.builder();
        // Insertion-ordered (LinkedHashMap inside CompoundTag) so the decoded snapshot preserves
        // the encode order — keeps test fixtures stable and lets listeners depend on iteration
        // order when that's meaningful (rare, but cheap to guarantee).
        Map<String, Object> staged = new LinkedHashMap<>();
        for (String key : tag.keySet()) {
            Tag child = tag.get(key);
            if (child instanceof BoolTag bt) {
                staged.put(key, bt.getAsBoolean());
            } else if (child instanceof ListTag lt && isEncodedLong(lt)) {
                staged.put(key, decodeLong(lt));
            } else if (child instanceof IntTag it) {
                staged.put(key, (long) it.getAsInt());
            } else if (child instanceof DoubleTag dt) {
                staged.put(key, dt.getAsDouble());
            } else if (child instanceof StringTag st) {
                staged.put(key, st.getAsString());
            }
            // Any other tag type is silently skipped — the on-wire format only ever produces the
            // four kinds above, so a non-matching tag is necessarily junk from a future / malicious
            // client and is safer dropped than coerced.
        }
        for (Map.Entry<String, Object> e : staged.entrySet()) {
            b.putRaw(e.getKey(), e.getValue());
        }
        return b.build();
    }

    /** Encodes a 64-bit long as a two-element {@link ListTag} {@code [hi32, lo32]}. */
    private static ListTag encodeLong(long value) {
        ListTag list = new ListTag();
        list.add(new IntTag((int) (value >> 32)));
        list.add(new IntTag((int) value));
        return list;
    }

    /** A {@link ListTag} produced by {@link #encodeLong(long)}: exactly two {@link IntTag} children. */
    private static boolean isEncodedLong(ListTag list) {
        return list.size() == 2
                && list.get(0) instanceof IntTag
                && list.get(1) instanceof IntTag;
    }

    /** Inverse of {@link #encodeLong(long)}: reconstructs the original 64-bit value. */
    private static long decodeLong(ListTag list) {
        int hi = ((IntTag) list.get(0)).getAsInt();
        int lo = ((IntTag) list.get(1)).getAsInt();
        return ((long) hi << 32) | (lo & 0xFFFFFFFFL);
    }
}
