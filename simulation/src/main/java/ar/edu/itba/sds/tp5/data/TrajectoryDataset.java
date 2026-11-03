package ar.edu.itba.sds.tp5.data;

import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dataset experimental completo (formato de la catedra): encabezado "# framerate: F fps"
 * y filas "id frame x/m y/m markerID". Lectura estricta: cualquier fila malformada o duplicada es un error.
 * No se asume que los IDs sean consecutivos, ni que los frames de una persona sean continuos.
 */
public final class TrajectoryDataset {
    private static final Pattern FPS = Pattern.compile("^#\\s*framerate:\\s*([0-9.eE+-]+)\\s*fps\\s*$");

    public final double fps;
    public final String sha256;
    private final Map<String, NavigableMap<Integer, double[]>> byId;

    private TrajectoryDataset(double fps, String sha, Map<String, NavigableMap<Integer, double[]>> byId) {
        this.fps = fps;
        this.sha256 = sha;
        this.byId = byId;
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    public int personCount() {
        return byId.size();
    }

    /** frame -> {x, y}. */
    public NavigableMap<Integer, double[]> samples(String id) {
        NavigableMap<Integer, double[]> m = byId.get(id);
        if (m == null) {
            throw new ConfigException("La persona '" + id + "' no existe en el dataset experimental");
        }
        return m;
    }

    public static TrajectoryDataset read(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ConfigException("No se pudo leer el dataset experimental '" + file + "': " + e.getMessage());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        double fps = Double.NaN;
        Map<String, NavigableMap<Integer, double[]>> by = new HashMap<>();
        int ln = 0;
        for (String raw : text.split("\\R")) {
            ln++;
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                Matcher m = FPS.matcher(line);
                if (m.matches()) {
                    fps = Double.parseDouble(m.group(1));
                }
                continue;
            }
            String[] p = line.split("\\s+");
            if (p.length != 5) {
                throw new ConfigException("Dataset " + file + " linea " + ln + ": se esperaban 5 columnas y hay " + p.length);
            }
            int frame;
            double x;
            double y;
            try {
                frame = Integer.parseInt(p[1]);
                x = Double.parseDouble(p[2]);
                y = Double.parseDouble(p[3]);
            } catch (NumberFormatException e) {
                throw new ConfigException("Dataset " + file + " linea " + ln + ": valor numerico invalido");
            }
            if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
                throw new ConfigException("Dataset " + file + " linea " + ln + ": coordenada no finita");
            }
            NavigableMap<Integer, double[]> t = by.computeIfAbsent(p[0], k -> new TreeMap<>());
            if (t.put(frame, new double[] {x, y}) != null) {
                throw new ConfigException("Dataset " + file + " linea " + ln + ": frame " + frame + " duplicado para id " + p[0]);
            }
        }
        if (Double.isNaN(fps) || fps <= 0) {
            throw new ConfigException("Dataset " + file + ": falta el encabezado '# framerate: <F> fps'");
        }
        if (by.isEmpty()) {
            throw new ConfigException("Dataset " + file + ": sin datos");
        }
        return new TrajectoryDataset(fps, sha256(bytes), by);
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Of(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new ConfigException("No se pudo leer '" + file + "': " + e.getMessage());
        }
    }

    /** Solo para pruebas: construye un dataset en memoria. */
    public static TrajectoryDataset ofText(String text) {
        try {
            Path tmp = Files.createTempFile("ds", ".txt");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                return read(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
