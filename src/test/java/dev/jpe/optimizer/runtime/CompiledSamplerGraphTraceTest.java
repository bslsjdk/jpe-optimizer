package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompiledSamplerGraphTraceTest {
    public record TestSampler(Object left, Object right, float scale) {}
    public record LeafSampler(float constant) {}
    private record HiddenSampler(Object child) {}
    private static final class CustomSampler {}

    @Test
    void capturesRecordSamplerChildrenAndScalarParameters() {
        LeafSampler shared = new LeafSampler(2.5F);
        TestSampler root = new TestSampler(shared, shared, 3.0F);

        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root,
                        value -> value instanceof TestSampler || value instanceof LeafSampler, 16);

        assertEquals(2, snapshot.nodes().size());
        assertEquals(0, snapshot.rootId());
        assertEquals(java.util.List.of(1, 1), snapshot.nodes().get(0).children());
        assertEquals("3.0", snapshot.nodes().get(0).values().get(0).value());
        assertEquals("2.5", snapshot.nodes().get(1).values().get(0).value());
        assertEquals(0, snapshot.droppedNodes());
    }

    @Test
    void readsPrivateNestedSamplerRecordAccessors() {
        HiddenSampler root = new HiddenSampler(new LeafSampler(4.0F));
        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root,
                        value -> value instanceof HiddenSampler || value instanceof LeafSampler, 8);

        assertEquals(2, snapshot.nodes().size());
        assertEquals(java.util.List.of(1), snapshot.nodes().get(0).children());
        assertTrue(snapshot.nodes().get(0).limitation().isEmpty());
    }

    @Test
    void preservesUnknownNonRecordSamplerAsOpaqueLeaf() {
        CustomSampler root = new CustomSampler();
        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root, value -> value instanceof CustomSampler, 4);

        assertEquals(1, snapshot.nodes().size());
        assertTrue(snapshot.nodes().get(0).limitation().contains("not a record"));
    }

    @Test
    void enforcesNodeBudgetWithoutWalkingPastLimit() {
        LeafSampler first = new LeafSampler(1.0F);
        TestSampler root = new TestSampler(first, new LeafSampler(2.0F), 1.0F);

        CompiledSamplerGraphTrace.Snapshot snapshot =
                CompiledSamplerGraphTrace.capture(root,
                        value -> value instanceof TestSampler || value instanceof LeafSampler, 2);

        assertEquals(2, snapshot.nodes().size());
        assertEquals(1, snapshot.droppedNodes());
        assertTrue(snapshot.nodes().get(0).limitation().contains("budget"));
    }
}
