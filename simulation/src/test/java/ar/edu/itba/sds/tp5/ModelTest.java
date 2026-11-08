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

    // ---------------------------------------------------------------- elusion continua (A, lambda)

    private static final double L0 = AvoidanceLayer.LOOKAHEAD_L0;

    /** Lateral (derecha propia) del target temporal respecto de r + L0 e para un agente que avanza hacia +x. */
    private static double lateralRight(Agent a) {
        return -(a.tempY - a.y);   // derecha de +x es -y
    }

    @Test
    public void twoParametersMustBeFiniteAndPositive() {
        for (double[] bad : new double[][] {{0, 1}, {-1, 1}, {1, 0}, {1, -2}, {Double.NaN, 1}, {1, Double.POSITIVE_INFINITY}}) {
            try {
                new AvoidanceLayer(bad[0], bad[1]);
                org.junit.Assert.fail("debia fallar con " + bad[0] + ", " + bad[1]);
            } catch (IllegalArgumentException e) {
                // esperado
            }
        }
        new AvoidanceLayer(0.5, 1.0);
    }

    @Test
    public void offsetDecaysMonotonicallyWithDistanceAndEqualsAAtZero() {
        AvoidanceLayer l = new AvoidanceLayer(1.5, 2.0);
        assertEquals(1.5, l.offset(0), 1e-15);
        double prev = l.offset(0);
        for (double d = 0.1; d < 30; d += 0.1) {
            double o = l.offset(d);
            assertTrue(o > 0);
            assertTrue(o < prev);
            prev = o;
        }
        assertEquals(1.5 * Math.exp(-1.0), l.offset(2.0), 1e-15);
    }

    @Test
    public void doublingAdoublesOffsetAndLargerLambdaIncreasesIt() {
        AvoidanceLayer a1 = new AvoidanceLayer(0.7, 1.5);
        AvoidanceLayer a2 = new AvoidanceLayer(1.4, 1.5);
        assertEquals(2 * a1.offset(3.0), a2.offset(3.0), 1e-15);
        AvoidanceLayer wide = new AvoidanceLayer(0.7, 3.0);
        assertTrue(wide.offset(3.0) > a1.offset(3.0));
        assertTrue(wide.offset(0.0) == a1.offset(0.0));   // a d = 0 vale A, independiente de lambda
    }

    @Test
    public void targetFollowsTheFormulaAndDoesNotTouchStateOrSpeed() {
        AvoidanceLayer l = new AvoidanceLayer(0.8, 2.0);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.2);
        Agent b = agent(4, 0.3, -1, 0, -10, 0.3, 1.2);   // adelante, d ~ 4.01
        double vx = a.vx;
        double vy = a.vy;
        l.update(a, new Agent[] {a, b}, 0.02);
        assertTrue(a.hasTemporalTarget);
        double d = Math.hypot(4, 0.3);
        double off = 0.8 * Math.exp(-d / 2.0);
        assertEquals(L0, a.tempX - a.x, 1e-12);          // P = r + L0 e + a n, con e = +x y n = (0,-1)
        assertEquals(-off, a.tempY - a.y, 1e-12);
        assertEquals(Math.atan2(-off, L0), a.theta, 1e-12);
        assertTrue("el target avanza hacia el destino", a.tempX - a.x > 0);
        assertEquals(vx, a.vx, 0);
        assertEquals(vy, a.vy, 0);
        assertEquals(0.0, a.x, 0);
        assertEquals(1.2, a.vd, 0);   // modulo de la velocidad deseada intacto
    }

    @Test
    public void headOnPairTakesRespectiveRights() {
        AvoidanceLayer l = new AvoidanceLayer(0.8, 2.0);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        Agent b = agent(6, 0, -1, 0, -4, 0, 1.0);
        l.update(a, new Agent[] {a, b}, 0.02);
        l.update(b, new Agent[] {a, b}, 0.02);
        assertTrue("a esquiva hacia su derecha (-y)", a.tempY < a.y);
        assertTrue("b esquiva hacia SU derecha (+y)", b.tempY > b.y);
        assertEquals(-(a.tempY - a.y), b.tempY - b.y, 1e-12);   // simetria
    }

    @Test
    public void responseIsContinuousInDistance() {
        AvoidanceLayer l = new AvoidanceLayer(0.8, 2.0);
        double prev = Double.NaN;
        for (double x = 8.0; x > 0.2; x -= 0.001) {
            Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
            Agent b = agent(x, 0.1, -1, 0, -4, 0, 1.0);
            l.update(a, new Agent[] {a, b}, 0.02);
            double off = lateralRight(a);
            if (!Double.isNaN(prev)) {
                assertTrue("salto " + Math.abs(off - prev) + " en x=" + x, Math.abs(off - prev) < 2e-3);
            }
            prev = off;
        }
    }

    @Test
    public void encounterWeightIsContinuousAndEndsSmoothly() {
        assertEquals(1.0, AvoidanceLayer.encounterWeight(5.0), 0);
        assertEquals(1.0, AvoidanceLayer.encounterWeight(0.0), 0);
        assertEquals(0.0, AvoidanceLayer.encounterWeight(-AvoidanceLayer.TRANSITION_LENGTH), 0);
        assertEquals(0.0, AvoidanceLayer.encounterWeight(-50.0), 0);
        double prev = 1.0;
        for (double s = 0.0; s > -AvoidanceLayer.TRANSITION_LENGTH; s -= 1e-3) {
            double w = AvoidanceLayer.encounterWeight(s);
            assertTrue(w <= prev + 1e-15);
            assertTrue(prev - w < 5e-3);
            prev = w;
        }
        assertEquals(0.0, AvoidanceLayer.encounterWeight(Double.NaN), 0);
    }

    @Test
    public void afterCrossingTheEffectDecaysBackToTheFinalTarget() {
        AvoidanceLayer l = new AvoidanceLayer(1.0, 3.0);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        double prev = Double.POSITIVE_INFINITY;
        boolean cleared = false;
        for (double bx = 1.0; bx > -3.0; bx -= 0.05) {      // b pasa de adelante a atras
            Agent b = agent(bx, 0.4, -1, 0, -10, 0.4, 1.0);
            l.update(a, new Agent[] {a, b}, 0.02);
            double off = a.hasTemporalTarget ? lateralRight(a) : 0.0;
            if (bx < -1e-6) {
                assertTrue("el offset solo baja despues del cruce", off <= prev + 1e-12);
            }
            prev = off;
            cleared |= !a.hasTemporalTarget;
        }
        assertTrue("el encuentro termina y vuelve al target final", cleared);
        assertFalse(a.hasTemporalTarget);
        assertEquals(0.0, a.theta, 0);
    }

    @Test
    public void edgeCasesDoNotProduceNaN() {
        AvoidanceLayer l = new AvoidanceLayer(0.5, 1.0);
        Agent a = agent(0, 0, 1, 0, 10, 0, 1.0);
        l.update(a, new Agent[] {a}, 0.02);                    // sin vecino
        assertFalse(a.hasTemporalTarget);
        Agent same = agent(0, 0, -1, 0, -10, 0, 1.0);          // posiciones coincidentes (d = 0)
        l.update(a, new Agent[] {a, same}, 0.02);
        assertTrue(Double.isFinite(a.tempX) && Double.isFinite(a.tempY) && Double.isFinite(a.theta));
        Agent far = agent(1e9, 0, -1, 0, 0, 0, 1.0);           // distancia enorme
        l.update(a, new Agent[] {a, far}, 0.02);
        assertFalse(a.hasTemporalTarget);
        Agent nan = agent(Double.NaN, 0, 0, 0, 0, 0, 1.0);     // dato anomalo
        l.update(a, new Agent[] {a, nan}, 0.02);
        assertFalse(a.hasTemporalTarget);
        Agent arrived = agent(10, 0, 0, 0, 10, 0, 1.0);        // ya llego al destino
        l.update(arrived, new Agent[] {arrived, same}, 0.02);
        assertFalse(arrived.hasTemporalTarget);
    }
}
