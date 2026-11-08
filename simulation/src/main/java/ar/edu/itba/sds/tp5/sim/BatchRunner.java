package ar.edu.itba.sds.tp5.sim;

import ar.edu.itba.sds.tp5.config.Json;
import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import ar.edu.itba.sds.tp5.io.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Ejecuta una lista de tareas (evento, A, lambda) sobre una configuracion base, reutilizando el cache:
 * una tarea cuyo TXT ya esta completo NO se vuelve a simular. Es el unico punto por el que Python pide corridas.
 *
 * Archivo batch (JSON): {"baseConfig": "input/base_config.json", "tasks": [[evento, A, lambda], ...]}
 */
public final class BatchRunner {

    /** Una fila del resultado. */
    public static final class Row {
        public final int eventId;
        public final double amplitude;
        public final double lambda;
        public final String runId;
        public final String status; // cached | ran | missing | failed
        public final String path;
        public final long wallMs;
        public final String message;

        Row(int e, double amplitude, double lambda, String id, String st, String path, long w, String msg) {
            this.eventId = e;
            this.amplitude = amplitude;
            this.lambda = lambda;
            this.runId = id;
            this.status = st;
            this.path = path;
            this.wallMs = w;
            this.message = msg;
        }
    }

    private BatchRunner() { }

    @SuppressWarnings("unchecked")
    public static List<Row> run(Path root, Path batchFile, boolean dryRun, int threads) throws IOException {
        Object parsed;
        try {
            parsed = Json.parse(new String(Files.readAllBytes(batchFile), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Batch invalido '" + batchFile + "': " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new ConfigException("El batch debe ser un objeto JSON");
        }
        Map<String, Object> top = (Map<String, Object>) parsed;
        Object base = top.get("baseConfig");
        Object tasks = top.get("tasks");
        if (!(base instanceof String) || !(tasks instanceof List)) {
            throw new ConfigException("El batch requiere 'baseConfig' (string) y 'tasks' (lista de [evento, A, lambda])");
        }
        Path basePath = Path.of((String) base);
        if (!basePath.isAbsolute()) {
            basePath = root.resolve(basePath);
        }
        final SimulationConfig baseCfg = SimulationConfig.fromFile(basePath);
        final ExperimentContext ctx = new ExperimentContext(root, baseCfg);

        List<double[]> list = new ArrayList<>();
        for (Object o : (List<Object>) tasks) {
            if (!(o instanceof List) || ((List<Object>) o).size() != 3) {
                throw new ConfigException("Cada tarea debe ser [evento, A, lambda]");
            }
            List<Object> t = (List<Object>) o;
            list.add(new double[] {(Double) t.get(0), (Double) t.get(1), (Double) t.get(2)});
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        List<Future<Row>> futs = new ArrayList<>();
        for (double[] t : list) {
            futs.add(pool.submit(() -> one(baseCfg, ctx, (int) t[0], t[1], t[2], dryRun)));
        }
        List<Row> rows = new ArrayList<>();
        try {
            for (Future<Row> f : futs) {
                rows.add(f.get());
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Fallo el batch: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }

        if (!dryRun) {
            List<Manifest.Entry> es = new ArrayList<>();
            for (Row r : rows) {
                if (r.status.equals("ran") || r.status.equals("cached")) {
                    es.add(new Manifest.Entry(r.runId, r.eventId, r.amplitude, r.lambda, "ok", r.path, r.wallMs,
                        SimulationConfig.ALGORITHM_VERSION, basePath.getFileName().toString()));
                }
            }
            Manifest.merge(ctx.resolve(baseCfg.outputDir + "/manifest.tsv"), es);
        }
        return rows;
    }

    private static Row one(SimulationConfig base, ExperimentContext ctx, int ev, double amplitude, double lambda, boolean dry) {
        try {
            SimulationConfig cfg = base.with(ev, amplitude, lambda);
            RunExecutor.Outcome o = RunExecutor.execute(cfg, ctx, dry);
            return new Row(ev, amplitude, lambda, o.runId, o.status, o.relPath, o.wallMs, "");
        } catch (Exception e) {
            return new Row(ev, amplitude, lambda, "", "failed", "", 0, String.valueOf(e.getMessage()).replace('\t', ' ').replace('\n', ' '));
        }
    }

    public static void writeResult(Path file, List<Row> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("event_id\tamplitude_m\tlambda_m\trun_id\tstatus\tpath\twall_ms\tmessage");
        for (Row r : rows) {
            lines.add(String.format(Locale.ROOT, "%d\t%s\t%s\t%s\t%s\t%s\t%d\t%s",
                r.eventId, Double.toString(r.amplitude), Double.toString(r.lambda), r.runId, r.status, r.path, r.wallMs, r.message));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
