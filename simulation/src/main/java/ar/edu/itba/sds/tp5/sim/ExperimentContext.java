package ar.edu.itba.sds.tp5.sim;

import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import ar.edu.itba.sds.tp5.data.Event;
import ar.edu.itba.sds.tp5.data.EventBuilder;
import ar.edu.itba.sds.tp5.data.Passage;
import ar.edu.itba.sds.tp5.data.TrajectoryDataset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Datos experimentales cargados una sola vez (dataset + LEEME) y raiz de rutas relativas. */
public final class ExperimentContext {
    public final Path root;
    public final String dataPathRel;
    public final String readmePathRel;
    public final TrajectoryDataset dataset;
    public final List<Passage> passages;
    public final String readmeSha256;

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
    }

    public Path resolve(String rel) {
        Path p = java.nio.file.Paths.get(rel);
        return p.isAbsolute() ? p : root.resolve(p);
    }

    public Event event(SimulationConfig cfg) {
        if (cfg.eventId < 1 || cfg.eventId > passages.size()) {
            throw new ConfigException("eventId " + cfg.eventId + " fuera de rango (1.." + passages.size() + ")");
        }
        return EventBuilder.build(dataset, passages.get(cfg.eventId - 1), cfg.idOverrides, cfg.velocityWindowSamples);
    }
}
