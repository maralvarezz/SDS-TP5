package ar.edu.itba.sds.tp5.sim;

import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.data.Event;
import ar.edu.itba.sds.tp5.io.TextOutputWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Identifica, ejecuta (o reutiliza del cache) una corrida y persiste su TXT. */
public final class RunExecutor {

    /** Resultado de planificar/ejecutar una corrida. */
    public static final class Outcome {
        public final String runId;
        public final String relPath;
        public final String status; // cached | ran | missing
        public final long wallMs;
        public final Event event;

        Outcome(String runId, String relPath, String status, long wallMs, Event event) {
            this.runId = runId;
            this.relPath = relPath;
            this.status = status;
            this.wallMs = wallMs;
            this.event = event;
        }
    }

    private RunExecutor() { }

    /** Identificador estable: hash de TODO lo que afecta la trayectoria (config efectiva normalizada + datos + version). */
    public static String runId(SimulationConfig cfg, ExperimentContext ctx, Event ev) {
        StringBuilder sb = new StringBuilder();
        sb.append("algorithm=").append(SimulationConfig.ALGORITHM_VERSION).append('\n');
        sb.append("data_sha256=").append(ctx.dataset.sha256).append('\n');
        sb.append("readme_sha256=").append(ctx.readmeSha256).append('\n');
        sb.append("left=").append(ev.left.id).append("\nright=").append(ev.right.id).append('\n');
        sb.append("start=").append(ev.startFrame).append("\nend=").append(ev.endFrame).append('\n');
        Map<String, String> flat = new TreeMap<>(cfg.toFlat());
        flat.remove("dataPath");
        flat.remove("readmePath");
        flat.remove("outputDir");
        // solo los overrides de ESTE evento afectan la corrida
        flat.keySet().removeIf(k -> k.startsWith("idOverrides.") && !k.startsWith("idOverrides." + cfg.eventId + "."));
        for (Map.Entry<String, String> e : flat.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                h.append(String.format("%02x", d[i]));
            }
            return h.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String relPath(SimulationConfig cfg, String runId) {
        return cfg.outputDir + "/runs/ev" + String.format("%02d", cfg.eventId) + "/" + runId + ".txt";
    }

    /** dryRun: no simula; informa cached o missing. */
    public static Outcome execute(SimulationConfig cfg, ExperimentContext ctx, boolean dryRun) throws IOException {
        Event ev = ctx.event(cfg);
        String id = runId(cfg, ctx, ev);
        String rel = relPath(cfg, id);
        Path file = ctx.resolve(rel);
        if (TextOutputWriter.isComplete(file)) {
            return new Outcome(id, rel, "cached", 0, ev);
        }
        if (dryRun) {
            return new Outcome(id, rel, "missing", 0, ev);
        }
        long t0 = System.nanoTime();
        try (TextOutputWriter w = new TextOutputWriter(file, cfg.eventId, header(cfg, ctx, ev, id))) {
            SimulationEngine.Result r = SimulationEngine.run(cfg, ev, w);
            String footer = String.format(Locale.ROOT,
                "steps=%d outputs=%d dt_eff_s=%.9g duration_s=%.6f avoid_time_fraction=%.4f max_abs_theta_rad=%.4f wall_ms=%d",
                r.steps, r.outputs, r.dtEff, r.durationS, r.fractionTimeAvoiding, r.maxAbsThetaRad, r.wallNanos / 1_000_000);
            w.finish(footer);
            return new Outcome(id, rel, "ran", (System.nanoTime() - t0) / 1_000_000, ev);
        }
    }

    static List<String> header(SimulationConfig cfg, ExperimentContext ctx, Event ev, String runId) {
        int nSub = SimulationEngine.subSteps(cfg, ev.fps);
        double dtEff = 1.0 / ev.fps / nSub;
        int stride = nSub / cfg.outputPerFrame;
        List<String> h = new ArrayList<>();
        h.add("run_id: " + runId);
        h.add("algorithm_version: " + SimulationConfig.ALGORITHM_VERSION);
        h.add("model: " + cfg.model + " (SFM de Helbing-Farkas-Vicsek 2000 sin fuerza social) + capa de elusion (p1,p2)");
        h.add("event_id: " + cfg.eventId);
        h.add("seed: " + cfg.seed + " (el motor es determinista; la semilla se registra por trazabilidad)");
        h.add("units: lengths m, time s, velocities m/s, theta rad");
        h.add("p1_horizon_s: " + cfg.p1);
        h.add("p2_clearance_m: " + cfg.p2);
        h.add("dt_requested_s: " + cfg.dt);
        h.add(String.format(Locale.ROOT, "dt_effective_s: %.9g  (= (1/fps)/%d; cada intervalo experimental tiene %d pasos exactos)", dtEff, nSub, nSub));
        h.add(String.format(Locale.ROOT, "output_dt_s: %.9g  (cada %d pasos; %d salidas por intervalo experimental)", dtEff * stride, stride, cfg.outputPerFrame));
        h.add("physical_parameters: mass_kg=" + cfg.mass + " tau_s=" + cfg.tau + " k_kg_s-2=" + cfg.k
            + " kappa_kg_m-1_s-1=" + cfg.kappa + " radius_m=" + cfg.radius
            + " source=Helbing,Farkas,Vicsek 2000 Nature 407:487 (contacto granular, sin termino social); radius de la consigna");
        h.add("avoidance_numerics: angle_step_deg=" + cfg.angleStepDeg + " update_dt_s=" + cfg.avoidanceUpdateDt
            + " arrival_tolerance_m=" + cfg.arrivalTolerance + " (no calibrados)");
        h.add("velocity_window_samples: " + cfg.velocityWindowSamples);
        h.add("source_dataset: " + ctx.dataPathRel + " sha256=" + ctx.dataset.sha256);
        h.add("source_readme: " + ctx.readmePathRel + " sha256=" + ctx.readmeSha256);
        h.add(String.format(Locale.ROOT, "fps: %s", Double.toString(ev.fps)));
        h.add("start_frame: " + ev.startFrame);
        h.add("end_frame: " + ev.endFrame);
        h.add(String.format(Locale.ROOT, "time_range_s: 0 %.6f", ev.duration()));
        for (Event.AgentSpec a : ev.agents()) {
            h.add(String.format(Locale.ROOT,
                "agent: id=%s side=%s x0=%.6f y0=%.6f vx0=%.6f vy0=%.6f vd=%.6f target_x=%.6f target_y=%.6f",
                a.id, a.side, a.x0, a.y0, a.vx0, a.vy0, a.vd, a.targetX, a.targetY));
        }
        for (Map.Entry<String, String> o : cfg.idOverrides.entrySet()) {
            if (o.getKey().startsWith(cfg.eventId + ".")) {
                h.add("id_override: " + o.getKey() + "=" + o.getValue());
            }
        }
        for (String w : ev.warnings) {
            h.add("warning: " + w);
        }
        return h;
    }
}
