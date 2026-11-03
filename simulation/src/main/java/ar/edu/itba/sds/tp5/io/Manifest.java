package ar.edu.itba.sds.tp5.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Manifiesto de corridas (output/manifest.tsv). Una linea por run_id. Se reescribe de forma atomica.
 * Es un indice de conveniencia: la fuente de verdad es el .txt (encabezado + marcador END).
 */
public final class Manifest {
    public static final String HEADER = "run_id\tevent_id\tp1\tp2\tstatus\tpath\twall_ms\talgorithm_version";

    public static final class Entry {
        public final String runId;
        public final int eventId;
        public final double p1;
        public final double p2;
        public final String status;
        public final String path;
        public final long wallMs;
        public final String version;

        public Entry(String runId, int eventId, double p1, double p2, String status, String path, long wallMs, String version) {
            this.runId = runId;
            this.eventId = eventId;
            this.p1 = p1;
            this.p2 = p2;
            this.status = status;
            this.path = path;
            this.wallMs = wallMs;
            this.version = version;
        }

        String line() {
            return runId + "\t" + eventId + "\t" + p1 + "\t" + p2 + "\t" + status + "\t" + path + "\t" + wallMs + "\t" + version;
        }
    }

    private Manifest() { }

    public static Map<String, String> load(Path file) throws IOException {
        Map<String, String> m = new TreeMap<>();
        if (Files.isRegularFile(file)) {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.isEmpty() || l.startsWith("run_id")) {
                    continue;
                }
                int t = l.indexOf('\t');
                if (t > 0) {
                    m.put(l.substring(0, t), l);
                }
            }
        }
        return m;
    }

    /** Combina las entradas nuevas con las existentes (por run_id) y reescribe el archivo. */
    public static void merge(Path file, List<Entry> entries) throws IOException {
        Map<String, String> m = load(file);
        for (Entry e : entries) {
            m.put(e.runId, e.line());
        }
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.addAll(m.values());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
