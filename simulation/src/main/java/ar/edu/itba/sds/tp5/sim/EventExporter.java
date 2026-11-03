package ar.edu.itba.sds.tp5.sim;

import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.data.Event;
import ar.edu.itba.sds.tp5.io.Fmt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exporta los eventos experimentales ya recortados (ventana, huecos) a output/events/, con procedencia,
 * para que Python (analisis/animaciones) trabaje sobre exactamente los mismos datos que usa el motor.
 * Tambien escribe events_summary.tsv con la validacion LEEME vs trayectorias.
 */
public final class EventExporter {
    private EventExporter() { }

    public static void export(SimulationConfig cfg, ExperimentContext ctx, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        List<String> summary = new ArrayList<>();
        summary.add("event_id\tleft_id\tright_id\tstart_frame\tend_frame\tduration_s\tn_left\tn_right\tmissing_left\tmissing_right"
            + "\tmin_dist_recomputed_m\tmin_dist_leeme_m\tvd_left\tvd_right\tv0_speed_left\tv0_speed_right\tstatus\twarnings");
        for (int e = 1; e <= ctx.passages.size(); e++) {
            SimulationConfig c = cfg.with(e, cfg.p1, cfg.p2);
            Event ev;
            try {
                ev = ctx.event(c);
            } catch (RuntimeException ex) {
                summary.add(e + "\t" + ctx.passages.get(e - 1).leftId + "\t" + ctx.passages.get(e - 1).rightId
                    + "\t\t\t\t\t\t\t\t\t" + ctx.passages.get(e - 1).minDistanceReported + "\t\t\t\t\tERROR\t"
                    + String.valueOf(ex.getMessage()).replace('\t', ' ').replace('\n', ' '));
                continue;
            }
            writeEvent(ctx, ev, outDir.resolve(String.format("event_%02d.txt", e)));
            Event.AgentSpec l = ev.left;
            Event.AgentSpec r = ev.right;
            summary.add(String.format(Locale.ROOT, "%d\t%s\t%s\t%d\t%d\t%.4f\t%d\t%d\t%d\t%d\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\tOK\t%s",
                e, l.id, r.id, ev.startFrame, ev.endFrame, ev.duration(), l.frames.length, r.frames.length,
                l.missingFrames, r.missingFrames, ev.minDistanceRecomputed, ev.passage.minDistanceReported,
                l.vd, r.vd, Math.hypot(l.vx0, l.vy0), Math.hypot(r.vx0, r.vy0), String.join(" | ", ev.warnings)));
        }
        Files.write(outDir.resolve("events_summary.tsv"), summary, StandardCharsets.UTF_8);
    }

    private static void writeEvent(ExperimentContext ctx, Event ev, Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# format: sds-tp5-event v1");
        lines.add("# note: trayectorias experimentales recortadas a la ventana del evento; archivo DERIVADO (no editar)");
        lines.add("# event_id: " + ev.eventId);
        lines.add("# source_dataset: " + ctx.dataPathRel + " sha256=" + ctx.dataset.sha256);
        lines.add("# source_readme: " + ctx.readmePathRel + " sha256=" + ctx.readmeSha256);
        lines.add("# fps: " + ev.fps);
        lines.add("# start_frame: " + ev.startFrame);
        lines.add("# end_frame: " + ev.endFrame);
        lines.add(String.format(Locale.ROOT, "# duration_s: %.6f", ev.duration()));
        for (Event.AgentSpec a : ev.agents()) {
            lines.add(String.format(Locale.ROOT,
                "# agent: id=%s side=%s x0=%.6f y0=%.6f vx0=%.6f vy0=%.6f vd=%.6f target_x=%.6f target_y=%.6f missing_frames=%d",
                a.id, a.side, a.x0, a.y0, a.vx0, a.vy0, a.vd, a.targetX, a.targetY, a.missingFrames));
        }
        for (String w : ev.warnings) {
            lines.add("# warning: " + w);
        }
        lines.add("# columns: frame t_s agent_id side x_m y_m");
        StringBuilder sb = new StringBuilder();
        for (Event.AgentSpec a : ev.agents()) {
            for (int i = 0; i < a.frames.length; i++) {
                sb.setLength(0);
                sb.append(a.frames[i]).append(' ');
                Fmt.fixed(sb, (a.frames[i] - ev.startFrame) / ev.fps, 6);
                sb.append(' ').append(a.id).append(' ').append(a.side).append(' ');
                Fmt.fixed(sb, a.x[i], 5);
                sb.append(' ');
                Fmt.fixed(sb, a.y[i], 5);
                lines.add(sb.toString());
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
