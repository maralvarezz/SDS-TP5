package ar.edu.itba.sds.tp5.cli;

import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import ar.edu.itba.sds.tp5.io.Manifest;
import ar.edu.itba.sds.tp5.sim.BatchRunner;
import ar.edu.itba.sds.tp5.sim.EventExporter;
import ar.edu.itba.sds.tp5.sim.ExperimentContext;
import ar.edu.itba.sds.tp5.sim.RunExecutor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI del motor. Se ejecuta desde la raiz del repo (o con --root).
 *
 *   run           --config <json|txt> [--root dir]
 *   batch         --config <batch.json> [--root dir] [--threads n] [--dry-run] [--result out.tsv]
 *   export-events --config <json|txt> [--root dir] [--out dir]
 *
 * Codigos de salida: 0 ok; 1 error de ejecucion; 2 uso/configuracion invalida.
 */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.println("Uso: sds-tp5.jar <run|batch|export-events> --config <archivo> [--root dir] [opciones]\n"
                + "  run            ejecuta un evento (usa cache si el TXT ya esta completo)\n"
                + "  batch          ejecuta tareas [evento,A,lambda]; opciones: --threads n --dry-run --result out.tsv\n"
                + "  export-events  exporta los eventos experimentales recortados; opcion: --out dir");
            return args.length == 0 ? 2 : 0;
        }
        String cmd = args[0];
        Map<String, String> opt = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                System.err.println("Argumento inesperado: " + args[i]);
                return 2;
            }
            String k = args[i].substring(2);
            if (k.equals("dry-run")) {
                opt.put(k, "true");
            } else if (i + 1 < args.length) {
                opt.put(k, args[++i]);
            } else {
                System.err.println("Falta el valor de --" + k);
                return 2;
            }
        }
        if (!opt.containsKey("config")) {
            System.err.println("Falta --config <archivo>");
            return 2;
        }
        Path root = Paths.get(opt.getOrDefault("root", ".")).toAbsolutePath().normalize();
        Path config = Paths.get(opt.get("config"));
        if (!config.isAbsolute()) {
            config = root.resolve(config);
        }
        try {
            switch (cmd) {
                case "run": {
                    SimulationConfig cfg = SimulationConfig.fromFile(config);
                    ExperimentContext ctx = new ExperimentContext(root, cfg);
                    RunExecutor.Outcome o = RunExecutor.execute(cfg, ctx, false);
                    Manifest.merge(ctx.resolve(cfg.outputDir + "/manifest.tsv"), Collections.singletonList(
                        new Manifest.Entry(o.runId, cfg.eventId, cfg.amplitude, cfg.spatialRange, "ok", o.relPath, o.wallMs,
                            SimulationConfig.ALGORITHM_VERSION, config.getFileName().toString())));
                    System.out.println(o.status + " " + o.runId + " " + o.relPath);
                    return 0;
                }
                case "batch": {
                    int threads = Integer.parseInt(opt.getOrDefault("threads", "1"));
                    boolean dry = opt.containsKey("dry-run");
                    List<BatchRunner.Row> rows = BatchRunner.run(root, config, dry, threads);
                    if (opt.containsKey("result")) {
                        Path rp = Paths.get(opt.get("result"));
                        BatchRunner.writeResult(rp.isAbsolute() ? rp : root.resolve(rp), rows);
                    }
                    int ran = 0;
                    int cached = 0;
                    int missing = 0;
                    int failed = 0;
                    for (BatchRunner.Row r : rows) {
                        switch (r.status) {
                            case "ran": ran++; break;
                            case "cached": cached++; break;
                            case "missing": missing++; break;
                            default: failed++;
                        }
                    }
                    System.out.println("tasks=" + rows.size() + " ran=" + ran + " cached=" + cached + " missing=" + missing + " failed=" + failed);
                    return failed > 0 ? 1 : 0;
                }
                case "export-events": {
                    SimulationConfig cfg = SimulationConfig.fromFile(config);
                    ExperimentContext ctx = new ExperimentContext(root, cfg);
                    Path out = ctx.resolve(opt.getOrDefault("out", cfg.outputDir + "/events"));
                    EventExporter.export(cfg, ctx, out);
                    System.out.println("eventos exportados en " + out);
                    return 0;
                }
                default:
                    System.err.println("Comando desconocido: " + cmd);
                    return 2;
            }
        } catch (ConfigException e) {
            System.err.println("ERROR de configuracion/entrada: " + e.getMessage());
            return 2;
        } catch (NumberFormatException e) {
            System.err.println("ERROR: valor numerico invalido en opciones: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("ERROR de ejecucion: " + e);
            return 1;
        }
    }
}
