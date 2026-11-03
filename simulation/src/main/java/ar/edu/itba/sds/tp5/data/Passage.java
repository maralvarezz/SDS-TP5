package ar.edu.itba.sds.tp5.data;

import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Una pasada (evento) segun el LEEME de la catedra. Los IDs se guardan como texto: "V?" es ambiguo. */
public final class Passage {
    public static final String AMBIGUOUS = "V?";

    public final int index;
    public final String leftId;
    public final String rightId;
    public final int frameStart;
    public final int frameEnd;
    public final double minDistanceReported;

    public Passage(int index, String leftId, String rightId, int frameStart, int frameEnd, double minDistanceReported) {
        this.index = index;
        this.leftId = leftId;
        this.rightId = rightId;
        this.frameStart = frameStart;
        this.frameEnd = frameEnd;
        this.minDistanceReported = minDistanceReported;
    }

    private static final Pattern LINE = Pattern.compile(
        "^\\s*prueba\\s+(\\d+):\\s*sale izquierda\\s+(\\S+),\\s*sale derecha\\s+(\\S+),\\s*cuadros\\s+(\\d+)-(\\d+),\\s*"
            + "distancia minima\\s+([0-9.]+)\\s*m\\s*$");
    private static final Pattern COUNT = Pattern.compile("^\\s*Pruebas/pasadas:\\s*(\\d+)\\s*$");

    public static List<Passage> readLeeme(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("No se pudo leer el LEEME '" + file + "': " + e.getMessage());
        }
        List<Passage> out = new ArrayList<>();
        int declared = -1;
        for (String l : lines) {
            Matcher c = COUNT.matcher(l);
            if (c.matches()) {
                declared = Integer.parseInt(c.group(1));
                continue;
            }
            if (l.trim().startsWith("prueba")) {
                Matcher m = LINE.matcher(l);
                if (!m.matches()) {
                    throw new ConfigException("LEEME " + file + ": linea de prueba no reconocida: '" + l + "'");
                }
                int a = Integer.parseInt(m.group(4));
                int b = Integer.parseInt(m.group(5));
                if (b <= a) {
                    throw new ConfigException("LEEME " + file + ": intervalo de cuadros invalido en prueba " + m.group(1));
                }
                out.add(new Passage(Integer.parseInt(m.group(1)), m.group(2), m.group(3), a, b, Double.parseDouble(m.group(6))));
            }
        }
        if (declared >= 0 && declared != out.size()) {
            throw new ConfigException("LEEME " + file + ": declara " + declared + " pasadas pero se leyeron " + out.size());
        }
        if (out.isEmpty()) {
            throw new ConfigException("LEEME " + file + ": no se encontraron pasadas");
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).index != i + 1) {
                throw new ConfigException("LEEME " + file + ": numeracion de pruebas no consecutiva en " + out.get(i).index);
            }
        }
        return out;
    }
}
