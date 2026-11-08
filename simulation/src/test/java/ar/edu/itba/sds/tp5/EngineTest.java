package ar.edu.itba.sds.tp5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ar.edu.itba.sds.tp5.cli.Main;
import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.data.Event;
import ar.edu.itba.sds.tp5.io.TextOutputWriter;
import ar.edu.itba.sds.tp5.model.Agent;
import ar.edu.itba.sds.tp5.sim.ExperimentContext;
import ar.edu.itba.sds.tp5.sim.RunExecutor;
import ar.edu.itba.sds.tp5.sim.SimulationEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EngineTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SimulationConfig cfg(Path root, double amplitude, double lambda) {
        return SimulationConfig.fromMap(SimulationConfig.parseTxt(
            "eventId = 1\namplitude = " + amplitude + "\nlambda = " + lambda + "\ndt = 0.0005\noutputPerFrame = 3\n"
                + "dataPath = docs/data.txt\nreadmePath = docs/leeme.txt\noutputDir = output\n"));
    }

    private static final class Rec implements SimulationEngine.StateSink {
        final List<double[]> rows = new ArrayList<>();   // t, frame, id, x, y, vx, vy

        @Override
        public void state(double t, double frame, Agent[] ag) {
            for (int i = 0; i < ag.length; i++) {
                rows.add(new double[] {t, frame, i, ag[i].x, ag[i].y, ag[i].vx, ag[i].vy});
            }
        }
    }

    @Test
    public void savesStatesExactlyAtExperimentalInstantsAndEndsAtLastFrame() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 5.0, 1.0, false);   // sin conflicto (y separadas 3 m)
        SimulationConfig c = cfg(root, 3.0, 0.8);
        ExperimentContext ctx = new ExperimentContext(root, c);
        Event ev = ctx.event(c);
        Rec r = new Rec();
        SimulationEngine.Result res = SimulationEngine.run(c, ev, r);
        assertEquals(ev.duration(), res.durationS, 1e-9);
        double[] last = r.rows.get(r.rows.size() - 1);
        assertEquals(ev.endFrame, last[1], 1e-9);
        // cada frame experimental entero aparece como una salida
        int integerFrames = 0;
        for (double[] row : r.rows) {
            if (row[2] == 0 && Math.abs(row[1] - Math.rint(row[1])) < 1e-9) {
                integerFrames++;
                assertEquals((Math.rint(row[1]) - ev.startFrame) / TestSupport.FPS, row[0], 1e-9);
            }
        }
        assertEquals(ev.endFrame - ev.startFrame + 1, integerFrames);
        assertEquals(res.outputs, r.rows.size() / 2);
        // condicion inicial = experimental
        assertEquals(ev.left.x0, r.rows.get(0)[3], 0);
        assertEquals(ev.left.vx0, r.rows.get(0)[5], 0);
    }

    @Test
    public void freeAgentsReachTheirFinalTarget() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 5.0, 1.0, false);
        SimulationConfig c = cfg(root, 3.0, 0.8);
        ExperimentContext ctx = new ExperimentContext(root, c);
        Event ev = ctx.event(c);
        Rec r = new Rec();
        SimulationEngine.run(c, ev, r);
        double[] a = r.rows.get(r.rows.size() - 2);
        double[] b = r.rows.get(r.rows.size() - 1);
        assertEquals(ev.left.targetX, a[3], 0.1);
        assertEquals(ev.left.targetY, a[4], 0.1);
        assertEquals(ev.right.targetX, b[3], 0.1);
    }

    @Test
    public void headOnPairAvoidsAndKeepsClearanceWithoutOverlap() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 2.0, 1.0, false);   // frontal exacto
        SimulationConfig c = cfg(root, 4.0, 0.8);
        ExperimentContext ctx = new ExperimentContext(root, c);
        Event ev = ctx.event(c);
        Rec r = new Rec();
        SimulationEngine.Result res = SimulationEngine.run(c, ev, r);
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < r.rows.size(); i += 2) {
            double[] a = r.rows.get(i);
            double[] b = r.rows.get(i + 1);
            min = Math.min(min, Math.hypot(a[3] - b[3], a[4] - b[4]));
        }
        assertTrue("distancia minima " + min, min > 0.5);    // sin solapamiento (2r = 0.5)
        assertTrue(res.fractionTimeAvoiding > 0);
        assertTrue(res.maxAbsThetaRad > 0);
    }

    @Test
    public void sameConfigSameTrajectoryBitForBit() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 2.05, 1.0, false);
        SimulationConfig c = cfg(root, 3.0, 0.7);
        ExperimentContext ctx = new ExperimentContext(root, c);
        Event ev = ctx.event(c);
        Rec r1 = new Rec();
        Rec r2 = new Rec();
        SimulationEngine.run(c, ev, r1);
        SimulationEngine.run(c, ev, r2);
        assertEquals(r1.rows.size(), r2.rows.size());
        for (int i = 0; i < r1.rows.size(); i++) {
            assertArrayEqualsExact(r1.rows.get(i), r2.rows.get(i));
        }
    }

    private static void assertArrayEqualsExact(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            assertEquals(Double.doubleToLongBits(a[i]), Double.doubleToLongBits(b[i]));
        }
    }

    @Test
    public void cacheAndRunIdBehaviour() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 2.05, 1.0, false);
        SimulationConfig c = cfg(root, 3.0, 0.7);
        ExperimentContext ctx = new ExperimentContext(root, c);
        assertEquals("missing", RunExecutor.execute(c, ctx, true).status);
        RunExecutor.Outcome o1 = RunExecutor.execute(c, ctx, false);
        assertEquals("ran", o1.status);
        assertEquals("cached", RunExecutor.execute(c, ctx, false).status);
        Path f = ctx.resolve(o1.relPath);
        assertTrue(TextOutputWriter.isComplete(f));
        // distinto A / lambda / dt -> otro id; mismo config -> mismo id
        assertNotEquals(o1.runId, RunExecutor.execute(cfg(root, 3.5, 0.7), ctx, true).runId);
        assertNotEquals(o1.runId, RunExecutor.execute(cfg(root, 3.0, 0.75), ctx, true).runId);
        assertEquals(o1.runId, RunExecutor.execute(cfg(root, 3.0, 0.7), ctx, true).runId);
        // un archivo incompleto (sin marcador END) no cuenta como cache
        String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        Files.write(f, text.substring(0, text.lastIndexOf("# END")).getBytes(StandardCharsets.UTF_8));
        assertFalse(TextOutputWriter.isComplete(f));
        assertEquals("ran", RunExecutor.execute(c, ctx, false).status);
        // el TXT tiene encabezado y columnas esperadas
        String full = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(full.contains("# format: sds-tp5-trajectory v1"));
        assertTrue(full.contains("# columns: event_id frame time_s agent_id x_m y_m vx_m_s vy_m_s radius_m theta_rad"));
        assertTrue(full.contains("# seed: 0"));
    }

    @Test
    public void cliReturnsNonZeroOnErrors() throws Exception {
        Path root = tmp.getRoot().toPath();
        assertEquals(2, Main.run(new String[] {}));
        assertEquals(2, Main.run(new String[] {"run"}));
        assertEquals(2, Main.run(new String[] {"run", "--config", root.resolve("nada.json").toString()}));
        TestSupport.writeHeadOn(root, 2.0, 2.0, 1.0, false);
        Path cfgFile = root.resolve("c.json");
        Files.write(cfgFile, TestSupport.configJson(3.0, 0.7).getBytes(StandardCharsets.UTF_8));
        assertEquals(0, Main.run(new String[] {"run", "--config", cfgFile.toString(), "--root", root.toString()}));
        assertTrue(Files.isRegularFile(root.resolve("output/manifest.tsv")));
        Files.delete(root.resolve("docs/data.txt"));
        assertEquals(2, Main.run(new String[] {"run", "--config", cfgFile.toString(), "--root", root.toString()}));
    }

    @Test
    public void cleanWindowRestrictsTheEventAndChangesRunId() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 2.05, 1.0, false);   // LEEME: cuadros 100-250
        Files.createDirectories(root.resolve("input"));
        Files.write(root.resolve("input/w.json"),
            "{\"windows\": {\"1\": {\"startFrame\": 130, \"endFrame\": 200, \"note\": \"x\"}}}".getBytes(StandardCharsets.UTF_8));
        SimulationConfig plain = cfg(root, 1.0, 1.0);
        SimulationConfig win = SimulationConfig.fromMap(SimulationConfig.parseTxt(
            "eventId = 1\namplitude = 1.0\nlambda = 1.0\ndt = 0.0005\noutputPerFrame = 3\n"
                + "dataPath = docs/data.txt\nreadmePath = docs/leeme.txt\noutputDir = output\nwindowsPath = input/w.json\n"));
        ExperimentContext c0 = new ExperimentContext(root, plain);
        ExperimentContext c1 = new ExperimentContext(root, win);
        Event e0 = c0.event(plain);
        Event e1 = c1.event(win);
        assertEquals(100, e0.startFrame);
        assertEquals(130, e1.startFrame);
        assertEquals(200, e1.endFrame);
        // el estado inicial y el target salen de la ventana limpia
        assertEquals(e1.left.x0, 0.5 + 1.0 * 30 / TestSupport.FPS, 1e-4);
        assertEquals(e1.left.targetX, 0.5 + 1.0 * 100 / TestSupport.FPS, 1e-4);
        assertNotEquals(RunExecutor.execute(plain, c0, true).runId, RunExecutor.execute(win, c1, true).runId);
        // ventana fuera del LEEME: error claro
        Files.write(root.resolve("input/w.json"),
            "{\"windows\": {\"1\": {\"startFrame\": 50, \"endFrame\": 200}}}".getBytes(StandardCharsets.UTF_8));
        try {
            new ExperimentContext(root, win).event(win);
            fail();
        } catch (SimulationConfig.ConfigException e) {
            assertTrue(e.getMessage().contains("ventana"));
        }
    }

    @Test
    public void avoidanceDisabledGivesFreeSfmReference() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 2.0, 1.0, false);
        SimulationConfig off = SimulationConfig.fromMap(SimulationConfig.parseTxt(
            "eventId = 1\namplitude = 1.0\nlambda = 1.0\ndt = 0.0005\noutputPerFrame = 3\navoidance.enabled = false\n"
                + "dataPath = docs/data.txt\nreadmePath = docs/leeme.txt\noutputDir = output\n"));
        ExperimentContext ctx = new ExperimentContext(root, off);
        SimulationEngine.Result res = SimulationEngine.run(off, ctx.event(off), new Rec());
        assertEquals(0.0, res.fractionTimeAvoiding, 0);
        assertNotEquals(RunExecutor.execute(off, ctx, true).runId, RunExecutor.execute(cfg(root, 1.0, 1.0), ctx, true).runId);
    }

    @Test
    public void postEventExtensionRunsUntilArrivalPlusHoldAndLeavesMetricsRunUntouched() throws Exception {
        Path root = tmp.getRoot().toPath();
        TestSupport.writeHeadOn(root, 2.0, 2.05, 1.0, false);
        SimulationConfig plain = cfg(root, 0.3, 3.0);
        SimulationConfig ext = SimulationConfig.fromMap(SimulationConfig.parseTxt(
            "eventId = 1\namplitude = 0.3\nlambda = 3.0\ndt = 0.0005\noutputPerFrame = 3\n"
                + "dataPath = docs/data.txt\nreadmePath = docs/leeme.txt\noutputDir = output\n"
                + "animation.extendUntilArrival = true\nanimation.holdSeconds = 2.0\n"));
        ExperimentContext ctx = new ExperimentContext(root, plain);
        Event ev = ctx.event(plain);
        Rec r0 = new Rec();
        SimulationEngine.Result res0 = SimulationEngine.run(plain, ev, r0);
        Rec r1 = new Rec();
        SimulationEngine.Result res1 = SimulationEngine.run(ext, ev, r1);
        assertEquals(ev.duration(), res0.durationS, 1e-9);                 // sin extension: termina en el ultimo instante experimental
        assertTrue(res1.durationS >= res0.durationS + 2.0 - 1e-6);          // con extension: al menos la espera de 2 s
        // la parte comun es identica bit a bit (la extension no altera la dinamica del evento)
        for (int i = 0; i < r0.rows.size(); i++) {
            assertArrayEqualsExact(r0.rows.get(i), r1.rows.get(i));
        }
        // al final ambos agentes estan en su target y quietos
        double[] a = r1.rows.get(r1.rows.size() - 2);
        double[] b = r1.rows.get(r1.rows.size() - 1);
        assertEquals(ev.left.targetX, a[3], 0.03);
        assertEquals(ev.right.targetX, b[3], 0.03);
        assertTrue(Math.hypot(a[5], a[6]) < 0.05 && Math.hypot(b[5], b[6]) < 0.05);
        assertNotEquals(RunExecutor.execute(plain, ctx, true).runId, RunExecutor.execute(ext, ctx, true).runId);
    }
}
