package com.minecart.protocol.payload;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import com.minecart.protocol.payload.client.ElementInfoUpdatePayload;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.ui.panel.PanelSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadRoundTripTest {

    /**
     * M9: a long value larger than {@link Integer#MAX_VALUE} must survive an encode/decode round-trip
     * losslessly. Under the old {@code putInt(((Number) v).intValue())} path it was truncated to 32 bits.
     */
    @Test
    void elementInfoUpdate_longAboveIntRange_roundTripsLosslessly() {
        long big = (1L << 40) + 12345L; // ~1.1e12, well beyond 2^31
        PanelSnapshot snapshot = PanelSnapshot.builder()
                .put("count", big)
                .build();
        ElementInfoUpdatePayload original =
                new ElementInfoUpdatePayload(UUID.randomUUID(), UUID.randomUUID(), snapshot);

        CompoundTag wire = Payload.serialize(original);
        ElementInfoUpdatePayload decoded = Payload.deserialize(wire, ElementInfoUpdatePayload.class);

        assertEquals(big, decoded.getSnapshot().getLong("count").orElseThrow(),
                "long value must round-trip without 32-bit truncation");
    }

    /**
     * A1: an empty combine-cascade must be rejected at the sender (during save), not only at the
     * receiver after a wasted round-trip.
     */
    @Test
    void combineCascade_emptyPairs_rejectedAtSender() {
        CombineCascadePayload empty =
                new CombineCascadePayload(UUID.randomUUID(), null, List.of());
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> Payload.serialize(empty));
        assertTrue(ex.getMessage().contains(ProtocolStrings.TAG_COMBINE_PAIRS));
    }

    @Test
    void combineCascade_nonEmpty_roundTrips() {
        UUID world = UUID.randomUUID();
        CombineCascadePayload.CombinePair pair =
                new CombineCascadePayload.CombinePair(UUID.randomUUID(), UUID.randomUUID());
        CombineCascadePayload original = new CombineCascadePayload(world, null, List.of(pair));

        CompoundTag wire = Payload.serialize(original);
        CombineCascadePayload decoded = Payload.deserialize(wire, CombineCascadePayload.class);

        assertEquals(world, decoded.getWorldId());
        assertEquals(1, decoded.getPairs().size());
    }

    /**
     * H1: {@link AllPayloads#init()} force-registers every payload kind so a cold connection can
     * decode the first frame it sees.
     */
    @Test
    void allPayloadsInit_registersEveryKind() {
        assertDoesNotThrow(AllPayloads::init);
        assertNotNull(PayloadRegistry.getType(ProtocolStrings.PAYLOAD_ELEMENT_INFO_UPDATE));
        assertNotNull(PayloadRegistry.getType(ProtocolStrings.PAYLOAD_COMBINE_CASCADE));
        assertNotNull(PayloadRegistry.getType(ProtocolStrings.PAYLOAD_ROTATE_ELEMENT));
    }
}
