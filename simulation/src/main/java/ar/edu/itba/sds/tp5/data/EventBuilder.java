package ar.edu.itba.sds.tp5.data;

import ar.edu.itba.sds.tp5.config.SimulationConfig.ConfigException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeSet;

/**
 * Construye un {@link Event} a partir del dataset y de una pasada del LEEME.
 *
 * Reglas (documentadas porque la consigna no las fija):
 *  - Ventana del evento: frames del LEEME [f0, f1] en los que AMBOS peatones tienen dato.
 *  - Instante inicial: primer frame f de la ventana tal que ambos tienen datos en f, f+1, ..., f+K-1 (K = velocityWindowSamples),
 *    para poder estimar la velocidad inicial. Normalmente coincide con el primer frame comun; si no, se advierte.
 *  - Instante final: ultimo frame de la ventana con dato en ambos. Target = posicion experimental en ese frame.
 *  - Velocidad inicial: pendiente de la regresion lineal posicion-tiempo de las primeras K muestras.
 *  - vd: rapidez media = promedio de |dr|/dt entre muestras consecutivas (frames adyacentes) dentro de la ventana.
 *  - El tiempo fisico es t = (frame - frame_inicial)/fps.
 */
public final class EventBuilder {
    private EventBuilder() { }

    public static Event build(TrajectoryDataset ds, Passage p, Map<String, String> idOverrides, int velocityWindowSamples) {
        List<String> warnings = new ArrayList<>();
        String leftId = resolveId(p, "left", p.leftId, idOverrides, warnings);
        String rightId = resolveId(p, "right", p.rightId, idOverrides, warnings);

        NavigableMap<Integer, double[]> sl = ds.samples(leftId);
        NavigableMap<Integer, double[]> sr = ds.samples(rightId);

        TreeSet<Integer> common = new TreeSet<>();
        for (Integer f : sl.subMap(p.frameStart, true, p.frameEnd, true).keySet()) {
            if (sr.containsKey(f)) {
                common.add(f);
            }
        }
        if (common.isEmpty()) {
            throw new ConfigException("Evento " + p.index + ": los peatones " + leftId + " y " + rightId
                + " no comparten ningun frame en [" + p.frameStart + "," + p.frameEnd + "]");
        }
        int K = velocityWindowSamples;
        int start = -1;
        for (int f : common) {
            boolean ok = true;
            for (int j = 1; j < K && ok; j++) {
                ok = common.contains(f + j);
            }
            if (ok) {
                start = f;
                break;
            }
        }
        if (start < 0) {
            throw new ConfigException("Evento " + p.index + ": no hay " + K + " frames consecutivos comunes para estimar la velocidad inicial");
        }
        if (start != common.first()) {
            warnings.add("inicio desplazado de frame " + common.first() + " a " + start
                + " (no habia " + K + " muestras consecutivas en ambos peatones)");
        }
        int end = common.last();
        if (end - start < K) {
            throw new ConfigException("Evento " + p.index + ": ventana demasiado corta (" + start + ".." + end + ")");
        }

        Event.AgentSpec left = agent(ds, leftId, "left", start, end, K, warnings);
        Event.AgentSpec right = agent(ds, rightId, "right", start, end, K, warnings);

        double minD = Double.POSITIVE_INFINITY;
        for (int f : common.subSet(start, true, end, true)) {
            double[] a = sl.get(f);
            double[] b = sr.get(f);
            minD = Math.min(minD, Math.hypot(a[0] - b[0], a[1] - b[1]));
        }
        if (Math.abs(minD - p.minDistanceReported) > 0.011) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "distancia minima recalculada %.3f m difiere de la del LEEME %.3f m", minD, p.minDistanceReported));
        }
        return new Event(p.index, p, ds.fps, start, end, left, right, minD, warnings);
    }

    private static String resolveId(Passage p, String side, String declared, Map<String, String> overrides, List<String> warnings) {
        String key = p.index + "." + side;
        String ov = overrides.get(key);
        if (Passage.AMBIGUOUS.equals(declared)) {
            if (ov == null) {
                throw new ConfigException("Evento " + p.index + ": el LEEME marca el ID '" + side
                    + "' como ambiguo ('V?'). No se inventa un ID: verificarlo y declararlo en la configuracion "
                    + "con idOverrides." + key + " = <id>");
            }
            warnings.add("ID " + side + " ambiguo en el LEEME ('V?') resuelto por configuracion a '" + ov + "' (PENDIENTE DE VERIFICACION)");
            return ov;
        }
        if (ov != null && !ov.equals(declared)) {
            throw new ConfigException("Evento " + p.index + ": idOverrides." + key + "=" + ov
                + " contradice el LEEME (" + declared + ")");
        }
        return declared;
    }

    private static Event.AgentSpec agent(TrajectoryDataset ds, String id, String side, int start, int end, int K, List<String> warnings) {
        NavigableMap<Integer, double[]> sub = ds.samples(id).subMap(start, true, end, true);
        int n = sub.size();
        int[] fr = new int[n];
        double[] x = new double[n];
        double[] y = new double[n];
        int i = 0;
        for (Map.Entry<Integer, double[]> e : sub.entrySet()) {
            fr[i] = e.getKey();
            x[i] = e.getValue()[0];
            y[i] = e.getValue()[1];
            i++;
        }
        int missing = (end - start + 1) - n;
        if (missing > 0) {
            warnings.add("peaton " + id + ": " + missing + " frames sin dato dentro de la ventana (se usan solo los existentes)");
        }
        double fps = ds.fps;
        // velocidad inicial: regresion lineal sobre las primeras K muestras (consecutivas por construccion)
        double[] t = new double[K];
        for (int j = 0; j < K; j++) {
            t[j] = (fr[j] - fr[0]) / fps;
        }
        double vx = slope(t, x, K);
        double vy = slope(t, y, K);
        // rapidez media entre muestras consecutivas
        double sum = 0;
        int cnt = 0;
        for (int j = 1; j < n; j++) {
            if (fr[j] - fr[j - 1] == 1) {
                sum += Math.hypot(x[j] - x[j - 1], y[j] - y[j - 1]) * fps;
                cnt++;
            }
        }
        if (cnt == 0) {
            throw new ConfigException("Peaton " + id + ": sin pares de frames consecutivos para calcular la rapidez media");
        }
        return new Event.AgentSpec(id, side, fr, x, y, vx, vy, sum / cnt, missing);
    }

    static double slope(double[] t, double[] v, int n) {
        double mt = 0;
        double mv = 0;
        for (int i = 0; i < n; i++) {
            mt += t[i];
            mv += v[i];
        }
        mt /= n;
        mv /= n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (t[i] - mt) * (v[i] - mv);
            den += (t[i] - mt) * (t[i] - mt);
        }
        return num / den;
    }
}
