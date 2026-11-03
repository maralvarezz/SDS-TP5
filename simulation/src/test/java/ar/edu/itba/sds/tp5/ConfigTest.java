package ar.edu.itba.sds.tp5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConfigTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path write(String name, String text) throws Exception {
        File f = tmp.newFile(name);
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f.toPath();
    }

    @Test
    public void jsonAndTxtGiveSameEffectiveConfig() throws Exception {
        Path j = write("c.json", "{\"eventId\":3,\"p1\":2.5,\"p2\":0.75,\"dt\":0.0002,\"seed\":7,"
            + "\"physicalParameters\":{\"mass\":70,\"radius\":0.25},\"idOverrides\":{\"2\":{\"left\":\"1000\"}}}");
        Path t = write("c.txt", "# comentario\neventId = 3\np1 = 2.5\np2 = 0.75   # inline\ndt = 0.0002\nseed = 7\n"
            + "physicalParameters.mass = 70\nphysicalParameters.radius = 0.25\nidOverrides.2.left = 1000\n");
        SimulationConfig a = SimulationConfig.fromFile(j);
        SimulationConfig b = SimulationConfig.fromFile(t);
        assertEquals(a.canonical(), b.canonical());
        assertEquals(70.0, a.mass, 0);
        assertEquals("1000", a.idOverrides.get("2.left"));
    }

    @Test
    public void defaultsAreRecordedInEffectiveConfig() throws Exception {
        SimulationConfig c = SimulationConfig.fromFile(write("d.json", "{\"eventId\":1,\"p1\":1,\"p2\":1}"));
        assertTrue(c.canonical().contains("physicalParameters.k=120000.0"));
        assertTrue(c.canonical().contains("physicalParameters.radius=0.25"));
    }

    @Test
    public void rejectsMissingRequired() throws Exception {
        try {
            SimulationConfig.fromFile(write("m.json", "{\"eventId\":1,\"p1\":1}"));
            fail();
        } catch (ConfigException e) {
            assertTrue(e.getMessage().contains("p2"));
        }
    }

    @Test
    public void rejectsInvalidValuesAndUnknownKeys() throws Exception {
        String[] bad = {
            "{\"eventId\":1,\"p1\":-1,\"p2\":1}",
            "{\"eventId\":1,\"p1\":1,\"p2\":0}",
            "{\"eventId\":1,\"p1\":1,\"p2\":1,\"dt\":0.5}",
            "{\"eventId\":1,\"p1\":1,\"p2\":1,\"typo\":3}",
            "{\"eventId\":1,\"p1\":\"abc\",\"p2\":1}",
            "{\"eventId\":1,\"p1\":1,\"p2\":1,\"model\":\"CPM\"}",
            "{\"eventId\":1,\"p1\":1,\"p2\":1",
        };
        int i = 0;
        for (String s : bad) {
            try {
                SimulationConfig.fromFile(write("b" + (i++) + ".json", s));
                fail("debia fallar: " + s);
            } catch (ConfigException e) {
                // esperado
            }
        }
    }

    @Test(expected = ConfigException.class)
    public void missingFileIsClearError() {
        SimulationConfig.fromFile(new File(tmp.getRoot(), "no_existe.json").toPath());
    }
}
