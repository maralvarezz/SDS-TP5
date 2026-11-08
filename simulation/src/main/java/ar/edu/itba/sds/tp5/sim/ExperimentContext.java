package ar.edu.itba.sds.tp5.sim;

import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import ar.edu.itba.sds.tp5.data.Event;
import ar.edu.itba.sds.tp5.data.EventBuilder;
import ar.edu.itba.sds.tp5.data.Passage;
import ar.edu.itba.sds.tp5.data.TrajectoryDataset;
import ar.edu.itba.sds.tp5.config.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Datos experimentales cargados una sola vez (dataset + LEEME) y raiz de rutas relativas. */
public final class ExperimentContext {
    public final Path root;
    public final String dataPathRel;
    public final String readmePathRel;
    public final TrajectoryDataset dataset;
    public final List<Passage> passages;
    public final String readmeSha256;
    /** Ventanas de evento limpias {frame inicial, frame final}; vacio si no hay archivo de ventanas. */
    public final Map<Integer, int[]> windows;
    public final String windowsSha256;

    public ExperimentContext(Path root, SimulationConfig cfg) {
        this.root = root;
        this.dataPathRel = cfg.dataPath;
        this.readmePathRel = cfg.readmePath;
        Path data = resolve(cfg.dataPath);
        Path readme = resolve(cfg.readmePath);
        if (!Files.isRegularFile(data)) {
            throw new ConfigException("No existe el dataset experimental: " + cfg.dataPath + " (raiz: " + root + ")");
        }
        if (!Files.isRegularFile(readme)) {
            throw new ConfigException("No existe el LEEME: " + cfg.readmePath + " (raiz: " + root + ")");
        }
        this.dataset = TrajectoryDataset.read(data);
        this.passages = Passage.readLeeme(readme);
        this.readmeSha256 = TrajectoryDataset.sha256Of(readme);
        this.windows = new HashMap<>();
        if (cfg.windowsPath.isEmpty()) {
            this.windowsSha256 = "none";
        } else {
            Path w = resolve(cfg.windowsPath);
            if (!Files.isRegularFile(w)) {
                throw new ConfigException("No existe el archivo de ventanas de eventos: " + cfg.windowsPath);
            }
            this.windowsSha256 = TrajectoryDataset.sha256Of(w);
            readWindows(w);
        }
    }

    /** Formato: {"windows": {"<evento>": {"startFrame": n, "endFrame": m, ...}}} (los demas campos son informativos). */
    @SuppressWarnings("unchecked")
    private void readWindows(Path file) {
        try {
            Object parsed = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            Object top = parsed instanceof Map ? ((Map<String, Object>) parsed).get("windows") : null;
            if (!(top instanceof Map)) {
                throw new ConfigException("El archivo de ventanas debe tener un objeto 'windows'");
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) top).entrySet()) {
                Map<String, Object> w = (Map<String, Object>) e.getValue();
                int fs = (int) Math.rint((Double) w.get("startFrame"));
                int fe = (int) Math.rint((Double) w.get("endFrame"));
                if (fe <= fs) {
                    throw new ConfigException("Ventana invalida para el evento " + e.getKey() + ": " + fs + ".." + fe);
                }
                windows.put(Integer.parseInt(e.getKey()), new int[] {fs, fe});
            }
        } catch (java.io.IOException | RuntimeException ex) {
            if (ex instanceof ConfigException) {
                throw (ConfigException) ex;
            }
            throw new ConfigException("Archivo de ventanas invalido '" + file + "': " + ex);
        }
    }

    public Path resolve(String rel) {
        Path p = java.nio.file.Paths.get(rel);
        return p.isAbsolute() ? p : root.resolve(p);
    }

    public Event event(SimulationConfig cfg) {
        if (cfg.eventId < 1 || cfg.eventId > passages.size()) {
            throw new ConfigException("eventId " + cfg.eventId + " fuera de rango (1.." + passages.size() + ")");
        }
        return EventBuilder.build(dataset, passages.get(cfg.eventId - 1), cfg.idOverrides, cfg.velocityWindowSamples,
            windows.get(cfg.eventId));
    }
}
