package com.minecart.ui.panel;

import com.minecart.elements.component.BJTransistor;
import com.minecart.elements.edge.Battery;
import com.minecart.elements.edge.Capacitor;
import com.minecart.elements.edge.Diode;
import com.minecart.elements.edge.Resistor;
import com.minecart.elements.node.Junction;
import com.minecart.event.events.ElementInfoUpdateEvent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.registry.CircuitElementType;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RotationInfo;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the kind-based panel-fragment system: {@link InfoPanelRegistry#buildSchema} must
 * compose the cross-cutting fragments registered on {@link CircuitNode}, {@link CircuitEdge},
 * and {@link com.minecart.logic.CircuitComponent} with the type-specific
 * {@link InfoPanelDefinition} (e.g. the Resistor's resistance field). Also verifies that fragment
 * save handlers run on {@link ElementInfoUpdateEvent} dispatch.
 *
 * <p>Doesn't drive the renderer — that's display-module territory; here we just assert the schema
 * shape and the snapshot-apply behaviour.
 */
class PanelFragmentTest {

    @Test
    void buildSchema_freeNode_includesPositionAndLockFragmentRows() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode n = w.createNode(AllComponents.CONNECTION);
        n.setInfo(AllElementInfos.POSITION, new PositionInfo(3.0, 4.0));

        InfoPanelSchema schema = InfoPanelRegistry.buildSchema(n);
        assertNotNull(schema, "free node with PositionInfo should yield a panel schema");
        Set<String> keys = schema.getFields().stream().map(PanelField::getKey).collect(Collectors.toSet());
        assertTrue(keys.contains(CircuitNode.FIELD_POSITION_X));
        assertTrue(keys.contains(CircuitNode.FIELD_POSITION_Y));
        assertTrue(keys.contains(CircuitNode.FIELD_LOCK_FIXED));
    }

    @Test
    void buildSchema_freeNode_hardLockedPositionHidesCoordsAndLockToggle() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode n = w.createNode(AllComponents.CONNECTION);
        PositionInfo pos = new PositionInfo(1.0, 2.0);
        pos.setFixed(true);
        pos.setCanChangeFix(false); // hard-locked: panel must hide both rows
        n.setInfo(AllElementInfos.POSITION, pos);

        InfoPanelSchema schema = InfoPanelRegistry.buildSchema(n);
        if (schema != null) {
            Set<String> keys = schema.getFields().stream().map(PanelField::getKey).collect(Collectors.toSet());
            assertFalse(keys.contains(CircuitNode.FIELD_POSITION_X),
                    "hard-locked node should not advertise an editable X");
            assertFalse(keys.contains(CircuitNode.FIELD_LOCK_FIXED),
                    "hard-locked node should not advertise a lock toggle");
        }
    }

    @Test
    void buildSchema_resistor_combinesEdgeFragmentWithResistance() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        a.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        b.setInfo(AllElementInfos.POSITION, new PositionInfo(1.0, 0.0));
        Resistor r = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(r);

        InfoPanelSchema schema = InfoPanelRegistry.buildSchema(r);
        assertNotNull(schema);
        Set<String> keys = schema.getFields().stream().map(PanelField::getKey).collect(Collectors.toSet());
        // Edge fragment fields:
        assertTrue(keys.contains(CircuitEdge.FIELD_START_X));
        assertTrue(keys.contains(CircuitEdge.FIELD_END_Y));
        assertTrue(keys.contains(CircuitEdge.FIELD_LOCK_MODE));
        // Type-specific field:
        assertTrue(keys.contains("resistance"));
    }

    @Test
    void buildSchema_bjt_includesComponentFragmentFields() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(5.0, 6.0));
        bjt.setInfo(AllElementInfos.ROTATION, new RotationInfo(0.0));

        InfoPanelSchema schema = InfoPanelRegistry.buildSchema(bjt);
        assertNotNull(schema);
        Set<String> keys = schema.getFields().stream().map(PanelField::getKey).collect(Collectors.toSet());
        assertTrue(keys.contains("core:position.x"));
        assertTrue(keys.contains("core:position.y"));
        assertTrue(keys.contains("core:rotation.angle"));
        assertTrue(keys.contains("core:lock.mode"));
    }

    @Test
    void dispatch_freeNode_appliesPositionAndLockFromSnapshot() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        CircuitNode n = w.createNode(AllComponents.CONNECTION);
        n.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));

        PanelSnapshot snap = PanelSnapshot.builder()
                .put(CircuitNode.FIELD_POSITION_X, 7.5)
                .put(CircuitNode.FIELD_POSITION_Y, -2.25)
                .put(CircuitNode.FIELD_LOCK_FIXED, true)
                .build();
        level.post(new ElementInfoUpdateEvent(n, snap));

        PositionInfo p = n.getInfo(AllElementInfos.POSITION);
        // Fragment ordering: lock applied first, so a "lock + move" payload locks the node and the
        // subsequent position write is then refused (we don't allow moving a locked node via panel).
        // That's the intended behaviour — the snapshot's "fixed=true" wins.
        assertTrue(p.isFixed(), "lock toggle should fix the node");
        assertEquals(0.0, p.getX(), 1e-9, "position write must be refused once lock is set");
        assertEquals(0.0, p.getY(), 1e-9);
    }

    @Test
    void dispatch_freeNode_positionWithoutLock_applies() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        CircuitNode n = w.createNode(AllComponents.CONNECTION);
        n.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));

        PanelSnapshot snap = PanelSnapshot.builder()
                .put(CircuitNode.FIELD_POSITION_X, 7.5)
                .put(CircuitNode.FIELD_POSITION_Y, -2.25)
                .put(CircuitNode.FIELD_LOCK_FIXED, false)
                .build();
        level.post(new ElementInfoUpdateEvent(n, snap));

        PositionInfo p = n.getInfo(AllElementInfos.POSITION);
        assertFalse(p.isFixed());
        assertEquals(7.5, p.getX(), 1e-9);
        assertEquals(-2.25, p.getY(), 1e-9);
    }

    @Test
    void dispatch_component_positionEdit_rigidlyTranslatesInternalNodes() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        bjt.setInfo(AllElementInfos.ROTATION, new RotationInfo(0.0));
        // Stamp known port positions so we can verify the translation.
        bjt.getPort(0).setInfo(AllElementInfos.POSITION, new PositionInfo(-1.0, 0.0));
        bjt.getPort(1).setInfo(AllElementInfos.POSITION, new PositionInfo(1.0, 0.5));
        bjt.getCenter().setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));

        PanelSnapshot snap = PanelSnapshot.builder()
                .put("core:position.x", 10.0)
                .put("core:position.y", -4.0)
                .put("core:rotation.angle", 0.0)
                .put("core:lock.mode", LockMode.FREE.name())
                .put("core:lock.pivotX", 10.0)
                .put("core:lock.pivotY", -4.0)
                .build();
        level.post(new ElementInfoUpdateEvent(bjt, snap));

        assertEquals(10.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(-4.0, bjt.getInfo(AllElementInfos.POSITION).getY(), 1e-9);
        // Each internal node shifted by (+10, -4).
        assertEquals(9.0, bjt.getPort(0).getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(-4.0, bjt.getPort(0).getInfo(AllElementInfos.POSITION).getY(), 1e-9);
        assertEquals(11.0, bjt.getPort(1).getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(-3.5, bjt.getPort(1).getInfo(AllElementInfos.POSITION).getY(), 1e-9);
    }

    @Test
    void dispatch_portNodePositionEdit_translatesParentComponentThroughCascade() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        bjt.getPort(0).setInfo(AllElementInfos.POSITION, new PositionInfo(-1.0, 0.0));
        bjt.getPort(1).setInfo(AllElementInfos.POSITION, new PositionInfo(1.0, 0.0));
        bjt.getCenter().setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));

        // Edit port 0's panel: typed (X=4, Y=2). Engine should translate the BJT by (+5, +2) so
        // port 0 lands at (4, 2) and port 1 / centre follow rigidly.
        PanelSnapshot snap = PanelSnapshot.builder()
                .put(CircuitNode.FIELD_POSITION_X, 4.0)
                .put(CircuitNode.FIELD_POSITION_Y, 2.0)
                .put(CircuitNode.FIELD_LOCK_FIXED, false)
                .build();
        level.post(new ElementInfoUpdateEvent(bjt.getPort(0), snap));

        assertEquals(4.0, bjt.getPort(0).getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(2.0, bjt.getPort(0).getInfo(AllElementInfos.POSITION).getY(), 1e-9);
        assertEquals(6.0, bjt.getPort(1).getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(2.0, bjt.getPort(1).getInfo(AllElementInfos.POSITION).getY(), 1e-9);
        assertEquals(5.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);
    }

    @Test
    void dispatch_portNodePositionEdit_lockedComponent_refuses() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        bjt.getPort(0).setInfo(AllElementInfos.POSITION, new PositionInfo(-1.0, 0.0));
        bjt.getCenter().setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        LockInfo strict = new LockInfo();
        strict.setMode(LockMode.LOCKED);
        bjt.setInfo(AllElementInfos.LOCK, strict);

        PanelSnapshot snap = PanelSnapshot.builder()
                .put(CircuitNode.FIELD_POSITION_X, 99.0)
                .put(CircuitNode.FIELD_POSITION_Y, 99.0)
                .put(CircuitNode.FIELD_LOCK_FIXED, false)
                .build();
        level.post(new ElementInfoUpdateEvent(bjt.getPort(0), snap));

        // Refused: port stays at its anchor, component centre untouched.
        assertEquals(-1.0, bjt.getPort(0).getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);
    }

    @Test
    void dispatch_component_lockedThenTranslate_translateRefused() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        bjt.setInfo(AllElementInfos.ROTATION, new RotationInfo(0.0));
        bjt.getPort(0).setInfo(AllElementInfos.POSITION, new PositionInfo(-1.0, 0.0));
        bjt.getCenter().setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));

        // Fragment order: lock applied first → effective lock becomes LOCKED → translate refused.
        PanelSnapshot snap = PanelSnapshot.builder()
                .put("core:position.x", 50.0)
                .put("core:position.y", 50.0)
                .put("core:rotation.angle", 0.0)
                .put("core:lock.mode", LockMode.LOCKED.name())
                .put("core:lock.pivotX", 0.0)
                .put("core:lock.pivotY", 0.0)
                .build();
        level.post(new ElementInfoUpdateEvent(bjt, snap));

        LockInfo lock = bjt.getInfo(AllElementInfos.LOCK);
        assertNotNull(lock);
        assertEquals(LockMode.LOCKED, lock.getMode());
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9,
                "translate must be refused once the strict lock locks the component");
        assertEquals(-1.0, bjt.getPort(0).getInfo(AllElementInfos.POSITION).getX(), 1e-9);
    }

    @Test
    void treePanel_childCanRemoveParentFieldAndFilterSavePayload() {
        PanelFieldKey<Double> parentValue = PanelFieldKey.doubleKey("test:" + UUID.randomUUID() + ".parent");
        PanelFieldKey<Double> childValue = PanelFieldKey.doubleKey("test:" + UUID.randomUUID() + ".child");
        InfoPanelElementType<CircuitNode> parentType = new InfoPanelElementType<>(
                "test:" + UUID.randomUUID() + ".parentType", CircuitNode.class, InfoPanelTypes.NODE);
        InfoPanelElementType<CircuitNode> childType = new InfoPanelElementType<>(
                "test:" + UUID.randomUUID() + ".childType", CircuitNode.class, parentType);
        CircuitElementType<CircuitNode> elementType = CircuitElementType.build(
                "test:" + UUID.randomUUID() + ".element", CircuitNode::new);

        AtomicBoolean parentSaveRan = new AtomicBoolean(false);
        AtomicBoolean childSaveRan = new AtomicBoolean(false);
        InfoPanelRegistry.registerPanel(parentType, (node, builder) ->
                builder.add(new com.minecart.ui.panel.fields.NumberFieldSpec(parentValue, "Parent", 1.0),
                        (n, ctx) -> parentSaveRan.set(true)));
        InfoPanelRegistry.registerPanel(childType, (node, builder) -> {
            builder.remove(parentValue);
            builder.add(new com.minecart.ui.panel.fields.NumberFieldSpec(childValue, "Child", 2.0),
                    (n, ctx) -> childSaveRan.set(ctx.doubleValue(childValue).orElse(0.0) == 7.0));
        });
        InfoPanelRegistry.bind(elementType, childType);

        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        CircuitNode node = elementType.create(world);

        InfoPanelSchema schema = InfoPanelRegistry.buildSchema(node);
        assertNotNull(schema);
        Set<String> keys = schema.fieldKeys();
        assertFalse(keys.contains(parentValue.id()), "child panel type should remove parent field");
        assertTrue(keys.contains(childValue.id()), "child panel field should remain active");

        PanelSnapshot snapshot = PanelSnapshot.builder()
                .put(parentValue, 99.0)
                .put(childValue, 7.0)
                .build();
        level.post(new ElementInfoUpdateEvent(node, snapshot));

        assertFalse(parentSaveRan.get(), "removed parent field save must not consume stale payload values");
        assertTrue(childSaveRan.get(), "active child field save should consume its value");
    }

    @Test
    void buildSchema_allSpecificBuiltInsExposeEditableFields() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        a.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        b.setInfo(AllElementInfos.POSITION, new PositionInfo(1.0, 0.0));

        Battery battery = w.connect(AllComponents.BATTERY, a, b);
        Capacitor capacitor = w.connect(AllComponents.CAPACITOR, a, b);
        Diode diode = w.connect(AllComponents.DIODE, a, b);
        Junction junction = (Junction) w.createNode(AllComponents.JUNCTION);
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);

        assertTrue(InfoPanelRegistry.buildSchema(battery).fieldKeys().contains(Battery.FIELD_VOLTAGE.id()));
        assertTrue(InfoPanelRegistry.buildSchema(capacitor).fieldKeys().contains(Capacitor.FIELD_CAPACITANCE.id()));
        assertTrue(InfoPanelRegistry.buildSchema(diode).fieldKeys().contains(Diode.FIELD_FORWARD_RESISTANCE.id()));
        assertTrue(InfoPanelRegistry.buildSchema(junction).fieldKeys().contains(Junction.FIELD_MAX_CONNECTIONS.id()));
        assertTrue(InfoPanelRegistry.buildSchema(bjt).fieldKeys().contains(BJTransistor.FIELD_BETA.id()));
    }

    @Test
    void dispatch_specificBuiltInFieldsApply() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        InfoPanelRegistry.installLevelListener(level);
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        a.setInfo(AllElementInfos.POSITION, new PositionInfo(0.0, 0.0));
        b.setInfo(AllElementInfos.POSITION, new PositionInfo(1.0, 0.0));

        Battery battery = w.connect(AllComponents.BATTERY, a, b);
        Capacitor capacitor = w.connect(AllComponents.CAPACITOR, a, b);
        Diode diode = w.connect(AllComponents.DIODE, a, b);
        Junction junction = (Junction) w.createNode(AllComponents.JUNCTION);
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);

        level.post(new ElementInfoUpdateEvent(battery, PanelSnapshot.builder()
                .put(Battery.FIELD_VOLTAGE, 9.0)
                .put(Battery.FIELD_RESISTANCE, 0.5)
                .build()));
        level.post(new ElementInfoUpdateEvent(capacitor, PanelSnapshot.builder()
                .put(Capacitor.FIELD_CAPACITANCE, 2.5)
                .put(Capacitor.FIELD_CHARGE, 3.5)
                .put(Capacitor.FIELD_INTERNAL_RESISTANCE, 0.2)
                .build()));
        level.post(new ElementInfoUpdateEvent(diode, PanelSnapshot.builder()
                .put(Diode.FIELD_FORWARD_RESISTANCE, 0.1)
                .put(Diode.FIELD_REVERSE_RESISTANCE, 1000.0)
                .build()));
        level.post(new ElementInfoUpdateEvent(junction, PanelSnapshot.builder()
                .put(Junction.FIELD_MAX_CONNECTIONS, 4.0)
                .build()));
        level.post(new ElementInfoUpdateEvent(bjt, PanelSnapshot.builder()
                .put(BJTransistor.FIELD_BETA, 42.0)
                .build()));

        assertEquals(9.0, battery.get().getVoltage(), 1e-9);
        assertEquals(0.5, battery.get().getResistance(), 1e-9);
        assertEquals(2.5, capacitor.get().getCapacitance(), 1e-9);
        assertEquals(3.5, capacitor.get().getCharge(), 1e-9);
        assertEquals(0.2, capacitor.get().getInternalResistance(), 1e-9);
        assertEquals(0.1, diode.get().getForwardResistance(), 1e-9);
        assertEquals(1000.0, diode.get().getReverseResistance(), 1e-9);
        assertEquals(4, junction.get().getConnection());
        assertEquals(42.0, bjt.get().getBeta(), 1e-9);
    }
}
