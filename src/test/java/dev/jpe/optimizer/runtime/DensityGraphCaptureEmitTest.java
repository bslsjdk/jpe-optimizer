package dev.jpe.optimizer.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DensityGraphCaptureEmitTest {
    @Test
    void emitsTableBackedNodeAfterResolvingItsCoordinateChild() {
        DensityGraphCapture capture = new DensityGraphCapture();
        Object y = new Object();
        Object spline = new Object();
        capture.coordinate(y, 1);
        DensityGraphPayloadCapture tables = new DensityGraphPayloadCapture();
        int slot = tables.reserveSpline();
        capture.emit(spline, children -> DensityGraphIr.Node.spline(slot), y);

        DensityGraphCapture.Result result = capture.capture(spline);
        tables.defineMultipointSpline(slot, result.indexOf(y),
                new float[] {0.0f, 8.0f}, new float[] {0.0f, 0.0f}, new int[] {0, 0});
        DensityGraphIr ir = result.toIr(7L, tables.build());

        assertEquals(2, ir.nodes().size());
        assertEquals(DensityGraphIr.Y, ir.nodes().get(0).opcode());
        assertEquals(DensityGraphIr.SPLINE, ir.nodes().get(1).opcode());
        assertEquals(0, ir.nodes().get(1).inputA());
        assertEquals(1, ir.rootNode());
        assertEquals(1, ir.payload().splines().size());
        assertEquals(2, ir.payload().splinePoints().size());
        assertEquals(8.0f, ir.payload().splinePoints().get(1).location());
    }

    @Test
    void resolvesNaryChildrenInRegistrationOrder() {
        DensityGraphCapture capture = new DensityGraphCapture();
        Object x = new Object();
        Object y = new Object();
        Object z = new Object();
        Object lerp = new Object();
        capture.coordinate(x, 0);
        capture.coordinate(y, 1);
        capture.coordinate(z, 2);
        capture.emit(lerp, children -> DensityGraphIr.Node.lerp(children[0], children[1], children[2]), x, y, z);

        DensityGraphIr ir = capture.finish(lerp, 0L);

        assertEquals(DensityGraphIr.LERP, ir.nodes().get(3).opcode());
        assertEquals(0, ir.nodes().get(3).inputA());
        assertEquals(1, ir.nodes().get(3).inputB());
        assertEquals(2, ir.nodes().get(3).reserved());
    }

    @Test
    void rejectsChildrenThatWereNotCapturedFirst() {
        DensityGraphCapture capture = new DensityGraphCapture();
        assertThrows(IllegalStateException.class,
                () -> capture.emit(new Object(), children -> DensityGraphIr.Node.opaque(), new Object()));
    }

    @Test
    void strictCaptureStillRejectsUnregisteredSources() {
        DensityGraphCapture capture = new DensityGraphCapture();
        Object orphan = new Object();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> capture.finish(orphan, 0L));
        assertTrue(failure.getMessage().contains("Missing semantic adapter"));
    }
}
