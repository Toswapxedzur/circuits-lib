package com.minecart.display.render.registry;

import com.minecart.logic.CircuitNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderTreeBuilderTest {

    @Test
    void childCanReplaceRemoveAndReorderParentParts() {
        RenderPartKey first = new RenderPartKey("test:first");
        RenderPartKey second = new RenderPartKey("test:second");
        RenderPartKey third = new RenderPartKey("test:third");

        List<String> calls = new ArrayList<>();
        RenderTreeBuilder<CircuitNode> builder = new RenderTreeBuilder<>();
        builder.add(first, capture(calls, "parent-first"))
                .add(second, capture(calls, "parent-second"))
                .replace(second, capture(calls, "child-second"))
                .add(third, capture(calls, "child-third"))
                .moveBefore(third, second)
                .remove(first);
        builder.build().draw(new CircuitNode(null), null);

        assertEquals(List.of("child-third", "child-second"), calls);
    }

    private static RenderPart<CircuitNode> capture(List<String> calls, String value) {
        return new RenderPart<>() {
            @Override
            public void draw(CircuitNode element, RenderContext context) {
                calls.add(value);
            }
        };
    }
}
