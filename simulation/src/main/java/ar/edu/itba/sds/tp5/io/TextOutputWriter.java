package ar.edu.itba.sds.tp5.io;

import ar.edu.itba.sds.tp5.model.Agent;
import ar.edu.itba.sds.tp5.sim.SimulationEngine;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Escritura incremental de la trayectoria simulada en un .txt legible por maquina.
 * Se escribe a "<archivo>.tmp" y se renombra al final: un archivo con nombre definitivo y marcador
 * "# END status=ok" esta siempre completo. No calcula observables.
 *
 * Columnas: event_id frame time_s agent_id x_m y_m vx_m_s vy_m_s radius_m theta_rad
 *   frame   = frame experimental (fraccionario entre frames; entero en los instantes experimentales t_k)
 *   time_s  = tiempo fisico desde el inicio del evento
 *   theta   = desvio angular de la capa de elusion (0 = sin target temporal)
 */
public final class TextOutputWriter implements SimulationEngine.StateSink, AutoCloseable {
    public static final String FORMAT = "sds-tp5-trajectory v1";
    public static final String COLUMNS = "event_id frame time_s agent_id x_m y_m vx_m_s vy_m_s radius_m theta_rad";

    private final Path finalPath;
    private final Path tmpPath;
    private final BufferedWriter out;
    private final int eventId;
    private final StringBuilder sb = new StringBuilder(256);
    private long rows;
    private boolean closed;

    public TextOutputWriter(Path finalPath, int eventId, List<String> headerLines) throws IOException {
        this.finalPath = finalPath;
        this.tmpPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        this.eventId = eventId;
        Files.createDirectories(finalPath.toAbsolutePath().getParent());
        this.out = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8);
        out.write("# format: " + FORMAT + "\n");
        for (String h : headerLines) {
            out.write("# " + h + "\n");
        }
        out.write("# columns: " + COLUMNS + "\n");
    }

    @Override
    public void state(double timeS, double frameExp, Agent[] agents) {
        try {
            for (Agent a : agents) {
                sb.setLength(0);
                sb.append(eventId).append(' ');
                Fmt.fixed(sb, frameExp, 4);
                sb.append(' ');
                Fmt.fixed(sb, timeS, 6);
                sb.append(' ').append(a.id).append(' ');
                Fmt.fixed(sb, a.x, 6);
                sb.append(' ');
                Fmt.fixed(sb, a.y, 6);
                sb.append(' ');
                Fmt.fixed(sb, a.vx, 6);
                sb.append(' ');
                Fmt.fixed(sb, a.vy, 6);
                sb.append(' ');
                Fmt.fixed(sb, a.radius, 3);
                sb.append(' ');
                Fmt.fixed(sb, a.hasTemporalTarget ? a.theta : 0.0, 5);
                sb.append('\n');
                out.write(sb.toString());
                rows++;
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public long rows() {
        return rows;
    }

    /** Cierra con el marcador de finalizacion y publica el archivo definitivo. */
    public void finish(String footer) throws IOException {
        out.write("# END status=ok rows=" + rows + " " + footer + "\n");
        out.close();
        closed = true;
        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Descarta el parcial si la corrida fallo. */
    @Override
    public void close() throws IOException {
        if (!closed) {
            try {
                out.close();
            } finally {
                Files.deleteIfExists(tmpPath);
            }
        }
    }

    /** true si el archivo existe y termina con el marcador de corrida completa. */
    public static boolean isComplete(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            int n = (int) Math.min(len, 512);
            byte[] buf = new byte[n];
            f.seek(len - n);
            f.readFully(buf);
            String tail = new String(buf, StandardCharsets.UTF_8);
            return tail.contains("# END status=ok");
        } catch (IOException e) {
            return false;
        }
    }
}
