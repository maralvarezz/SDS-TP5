package ar.edu.itba.sds.tp5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ar.edu.itba.sds.tp5.avoidance.AvoidanceLayer;
import ar.edu.itba.sds.tp5.model.Agent;
import ar.edu.itba.sds.tp5.model.SfmModel;
import org.junit.Test;

public class ModelTest {
    private static Agent agent(double x, double y, double vx, double vy, double tx, double ty, double vd) {
        return new Agent("a", 80, 0.25, vd, tx, ty, x, y, vx, vy);
    }

    @Test
    public void drivingForceRelaxesTowardDesiredVelocity() {
        SfmModel m = new SfmModel(0.5, 1.2e5, 2.4e5, 0.02);
        Agent a = agent(0, 0, 0, 0, 10, 0, 1.0);
        double[] ax = new double[1];
        double[] ay = new double[1];
        m.accelerations(new Agent[] {a}, ax, ay);
        assertEquals(2.0, ax[0], 1e-12);   // (vd - v)/tau
        assertEquals(0.0, ay[0], 1e-12);
    }

    @Test
    public void noSocialForceWithoutContact() {
        SfmModel m = new SfmModel(0.5, 1.2e5, 2.4e5, 0.02);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        Agent b = agent(0.6, 0, -1, 0, -10, 0, 1.0);   // distancia 0.6 > 2r = 0.5: sin contacto
        double[] ax = new double[2];
        double[] ay = new double[2];
        m.accelerations(new Agent[] {a, b}, ax, ay);
        assertEquals(0.0, ax[0], 1e-12);
        assertEquals(0.0, ax[1], 1e-12);
    }

    @Test
    public void contactForceIsRepulsiveAndObeysNewtonThird() {
        SfmModel m = new SfmModel(0.5, 1.2e5, 2.4e5, 0.02);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        Agent b = agent(0.45, 0.0, -1, 0, -10, 0, 1.0);   // solapamiento 0.05
        double[] ax = new double[2];
        double[] ay = new double[2];
        m.accelerations(new Agent[] {a, b}, ax, ay);
        double fa = ax[0] * 80 - 80 * (1.0 - 1.0) / 0.5;   // driving = 0 (v = vd)
        assertEquals(-1.2e5 * 0.05, fa, 1e-6);              // empuja a 'a' hacia -x
        assertEquals(-ax[0], ax[1], 1e-9);                   // reaccion opuesta (misma masa)
    }

    @Test
    public void tangentialFrictionOpposesRelativeSlip() {
        SfmModel m = new SfmModel(0.5, 1.2e5, 2.4e5, 0.02);
        Agent a = agent(0, 0, 0, 0, 0, 0, 0.0);
        Agent b = agent(0.45, 0.0, 0, 1.0, 0.45, 5, 0.0);   // b se desliza hacia +y
        double[] ax = new double[2];
        double[] ay = new double[2];
        m.accelerations(new Agent[] {a, b}, ax, ay);
        // el termino tangencial arrastra a 'a' hacia +y (misma direccion que el deslizamiento de b)
        assertTrue(ay[0] > 0);
        assertTrue(ay[1] < 0);
    }

    // ---------------------------------------------------------------- elusion

    @Test
    public void noConflictKeepsFinalTarget() {
        AvoidanceLayer l = new AvoidanceLayer(3.0, 0.8, 1.0);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        Agent b = agent(10, 3.0, -1, 0, 0, 3, 1.0);   // pasa a 3 m
        l.update(a, new Agent[] {a, b}, 0.02);
        assertFalse(a.hasTemporalTarget);
        assertEquals(0.0, a.theta, 0);
    }

    @Test
    public void headOnConflictCreatesTemporaryTargetAwayFromNeighbour() {
        AvoidanceLayer l = new AvoidanceLayer(5.0, 0.8, 1.0);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        Agent b = agent(8, 0.2, -1, 0, 0, 0.2, 1.0);   // b a la izquierda de a, casi frontal
        double vxBefore = a.vx;
        l.update(a, new Agent[] {a, b}, 0.02);
        assertTrue(a.hasTemporalTarget);
        assertTrue("a debe esquivar hacia -y (lejos de b)", a.tempY < 0);
        assertTrue(Math.abs(a.theta) > 0);
        assertEquals("la capa no toca la velocidad ni la posicion", vxBefore, a.vx, 0);
        assertEquals(0.0, a.x, 0);
        // la direccion elegida resuelve el conflicto: la distancia minima prevista es >= p2
        double th = a.theta;
        double ux = Math.cos(th);
        double uy = Math.sin(th);
        double dx = 8;
        double dy = 0.2;
        double wx = -1 - ux;
        double wy = -uy;
        double tc = -(dx * wx + dy * wy) / (wx * wx + wy * wy);
        double d = Math.hypot(dx + wx * tc, dy + wy * tc);
        assertTrue("d=" + d, d >= 0.8 - 1e-9);
        // y es el giro mas chico: un paso menos todavia entra en conflicto
        double th2 = th - Math.signum(th) * Math.toRadians(1.0);
        double ux2 = Math.cos(th2);
        double uy2 = Math.sin(th2);
        double wx2 = -1 - ux2;
        double wy2 = -uy2;
        double tc2 = -(dx * wx2 + dy * wy2) / (wx2 * wx2 + wy2 * wy2);
        assertTrue(Math.hypot(dx + wx2 * tc2, dy + wy2 * tc2) < 0.8);
    }

    @Test
    public void conflictBeyondHorizonIsIgnoredAndReturnsToFinalTarget() {
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        Agent b = agent(8, 0.0, -1, 0, 0, 0, 1.0);
        AvoidanceLayer longH = new AvoidanceLayer(5.0, 0.8, 1.0);
        longH.update(a, new Agent[] {a, b}, 0.02);
        assertTrue(a.hasTemporalTarget);
        AvoidanceLayer shortH = new AvoidanceLayer(1.0, 0.8, 1.0);   // contacto previsto en t=4 s > T
        shortH.update(a, new Agent[] {a, b}, 0.02);
        assertFalse("al desaparecer el conflicto retoma el target final", a.hasTemporalTarget);
        // ya cruzados (alejandose): sin conflicto
        Agent c = agent(-2, 0.1, -1, 0, -10, 0, 1.0);
        longH.update(a, new Agent[] {a, c}, 0.02);
        assertFalse(a.hasTemporalTarget);
    }
}
