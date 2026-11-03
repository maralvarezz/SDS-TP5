package ar.edu.itba.sds.tp5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import ar.edu.itba.sds.tp5.data.Event;
import ar.edu.itba.sds.tp5.data.EventBuilder;
import ar.edu.itba.sds.tp5.data.Passage;
import ar.edu.itba.sds.tp5.data.TrajectoryDataset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DataTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsFramerateNonConsecutiveIdsAndGaps() {
        TrajectoryDataset ds = TrajectoryDataset.ofText("# framerate: 15.0192 fps\n# id frame x/m y/m markerID\n"
            + "7 10 1.0 2.0 7\n7 11 1.1 2.0 7\n7 20 2.0 2.0 7\n1000 10 5 5 -1\n");
        assertEquals(15.0192, ds.fps, 1e-12);
        assertEquals(2, ds.personCount());
        assertTrue(ds.has("1000"));
        assertEquals(3, ds.samples("7").size());
        assertEquals(20, (int) ds.samples("7").lastKey());
    }

    @Test
    public void strictReaderRejectsBadInput() {
        String[] bad = {
            "# id frame x y m\n1 1 0 0 1\n",                                   // sin framerate
            "# framerate: 15 fps\n1 1 0 0\n",                                   // columnas
            "# framerate: 15 fps\n1 1 0 0 1\n1 1 0 0 1\n",                      // frame duplicado
            "# framerate: 15 fps\n1 a 0 0 1\n",                                 // no numerico
            "# framerate: 15 fps\n1 1 NaN 0 1\n",                               // no finito
        };
        for (String s : bad) {
            try {
                TrajectoryDataset.ofText(s);
                fail("debia fallar: " + s);
            } catch (ConfigException e) {
                // esperado
            }
        }
    }

    @Test
    public void leemeParsesAmbiguousIdAndChecksCount() throws Exception {
        Path f = tmp.newFile("l.txt").toPath();
        Files.write(f, ("Personas: 3\nPruebas/pasadas: 2\n"
            + "  prueba   1: sale izquierda   3, sale derecha  36, cuadros 20068-20261, distancia minima 0.91 m\n"
            + "  prueba   2: sale izquierda  V?, sale derecha  25, cuadros 20208-20391, distancia minima 0.88 m\n")
            .getBytes(StandardCharsets.UTF_8));
        List<Passage> p = Passage.readLeeme(f);
        assertEquals(2, p.size());
        assertEquals(Passage.AMBIGUOUS, p.get(1).leftId);
        assertEquals(20068, p.get(0).frameStart);
        Files.write(f, ("Pruebas/pasadas: 5\n  prueba   1: sale izquierda   3, sale derecha  36, cuadros 1-2, distancia minima 0.9 m\n")
            .getBytes(StandardCharsets.UTF_8));
        try {
            Passage.readLeeme(f);
            fail();
        } catch (ConfigException e) {
            assertTrue(e.getMessage().contains("declara 5"));
        }
    }

    private static String track(String id, int f0, int f1, double x0, double vx, double y, int skipFrom, int skipTo) {
        StringBuilder sb = new StringBuilder();
        for (int f = f0; f <= f1; f++) {
            if (f >= skipFrom && f <= skipTo) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%s %d %.6f %.6f %s\n", id, f, x0 + vx * (f - f0) / 15.0, y, id));
        }
        return sb.toString();
    }

    @Test
    public void eventConditionsFromSyntheticTracks() {
        // izq: v=+1.2 m/s, frames 100..250; der: v=-0.8 m/s, frames 90..200 con hueco 150..160
        TrajectoryDataset ds = TrajectoryDataset.ofText("# framerate: 15 fps\n"
            + track("1", 100, 250, 0.5, 1.2, 2.0, -1, -1) + track("2", 90, 200, 9.5, -0.8, 2.1, 150, 160));
        Passage p = new Passage(1, "1", "2", 95, 260, 0.1);
        Event e = EventBuilder.build(ds, p, Collections.emptyMap(), 8);
        assertEquals(100, e.startFrame);
        assertEquals(200, e.endFrame);                       // ultimo frame comun
        assertEquals(1.2, e.left.vx0, 1e-6);                 // velocidad inicial por regresion
        assertEquals(0.0, e.left.vy0, 1e-9);
        assertEquals(-0.8, e.right.vx0, 1e-6);
        assertEquals(1.2, e.left.vd, 1e-6);                  // rapidez media
        assertEquals(0.8, e.right.vd, 1e-6);
        assertEquals(e.left.x[e.left.x.length - 1], e.left.targetX, 0);   // target = ultima posicion experimental
        assertEquals(11, e.right.missingFrames);
        assertEquals(100.0 / 15.0, e.duration(), 1e-12);
        assertEquals(0.5 + 1.2 * 100 / 15.0, e.left.targetX, 1e-5);
    }

    @Test
    public void meanSpeedIsNotMagnitudeOfMeanVelocity() {
        // ida y vuelta: rapidez media 1.5 m/s, velocidad media ~0
        StringBuilder sb = new StringBuilder("# framerate: 10 fps\n");
        for (int f = 0; f <= 20; f++) {
            double x = f <= 10 ? 0.15 * f : 0.15 * (20 - f);
            sb.append(String.format(Locale.ROOT, "1 %d %.6f 0 1\n2 %d %.6f 3 2\n", f, x, f, 5 - x));
        }
        TrajectoryDataset ds = TrajectoryDataset.ofText(sb.toString());
        Event e = EventBuilder.build(ds, new Passage(1, "1", "2", 0, 20, 1), Collections.emptyMap(), 4);
        assertEquals(1.5, e.left.vd, 1e-9);
        assertEquals(0.0, Math.abs(e.left.x[20] - e.left.x[0]) / 2.0, 1e-9);
    }

    @Test
    public void startShiftsWhenNoConsecutiveSamples() {
        TrajectoryDataset ds = TrajectoryDataset.ofText("# framerate: 15 fps\n"
            + track("1", 100, 200, 0.5, 1.0, 2.0, 101, 110) + track("2", 100, 200, 9.5, -1.0, 2.0, -1, -1));
        Event e = EventBuilder.build(ds, new Passage(1, "1", "2", 100, 200, 1), Collections.emptyMap(), 8);
        assertEquals(111, e.startFrame);
        boolean warned = false;
        for (String w : e.warnings) {
            warned |= w.contains("desplazado");
        }
        assertTrue(warned);
    }

    @Test
    public void ambiguousIdNeedsExplicitOverride() {
        TrajectoryDataset ds = TrajectoryDataset.ofText("# framerate: 15 fps\n"
            + track("1000", 100, 200, 0.5, 1.0, 2.0, -1, -1) + track("2", 100, 200, 9.5, -1.0, 2.0, -1, -1));
        Passage p = new Passage(1, Passage.AMBIGUOUS, "2", 100, 200, 1);
        try {
            EventBuilder.build(ds, p, Collections.emptyMap(), 8);
            fail("no debe inventar un ID");
        } catch (ConfigException e) {
            assertTrue(e.getMessage().contains("ambiguo"));
        }
        Map<String, String> ov = Collections.singletonMap("1.left", "1000");
        Event e = EventBuilder.build(ds, p, ov, 8);
        assertEquals("1000", e.left.id);
        assertTrue(e.warnings.toString().contains("PENDIENTE DE VERIFICACION"));
    }
}
