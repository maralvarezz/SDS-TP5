package ar.edu.itba.sds.tp5.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Configuracion efectiva de UNA corrida (un evento, un par p1/p2).
 *
 * JSON y TXT ("clave = valor", comentarios con #) se aplanan al mismo mapa de claves con puntos
 * y pasan por el mismo constructor, de modo que ambos formatos tienen exactamente la misma semantica.
 * Las claves desconocidas se rechazan (evita typos silenciosos).
 */
public final class SimulationConfig {

    public static final String MODEL_SFM = "SFM_NO_SOCIAL";
    /** Versión del algoritmo. Se incorpora al identificador de corrida: subirla invalida el cache. */
    public static final String ALGORITHM_VERSION = "1.0.0";

    // --- identificacion
    public final String model;
    public final int eventId;
    public final double p1;   // horizonte de anticipacion [s]
    public final double p2;   // distancia de paso de seguridad (centro a centro) [m]
    public final long seed;

    // --- tiempo
    public final double dt;            // paso de integracion objetivo [s]
    public final int outputPerFrame;   // salidas guardadas por intervalo experimental

    // --- rutas (relativas a --root)
    public final String dataPath;
    public final String readmePath;
    public final String outputDir;

    // --- parametros fisicos SFM (Helbing, Farkas & Vicsek 2000, Nature 407:487)
    public final double mass;     // kg
    public final double tau;      // s
    public final double k;        // kg s^-2
    public final double kappa;    // kg m^-1 s^-1
    public final double radius;   // m (consigna: 0.25)

    // --- procesamiento de datos
    public final int velocityWindowSamples;  // muestras para estimar la velocidad inicial

    // --- parametros numericos de la capa de elusion (NO se calibran)
    public final double angleStepDeg;
    public final double avoidanceUpdateDt;
    public final double arrivalTolerance;

    /** overrides de IDs ambiguos, ej. "2.left" -> "1000". Orden estable. */
    public final Map<String, String> idOverrides;

    private static final String[] KNOWN = {
        "model", "eventId", "p1", "p2", "seed", "dt", "outputPerFrame", "dataPath", "readmePath", "outputDir",
        "physicalParameters.mass", "physicalParameters.tau", "physicalParameters.k",
        "physicalParameters.kappa", "physicalParameters.radius",
        "data.velocityWindowSamples",
        "avoidance.angleStepDeg", "avoidance.updateDt", "avoidance.arrivalTolerance"
    };

    private SimulationConfig(Map<String, String> m) {
        for (String key : m.keySet()) {
            boolean ok = key.startsWith("idOverrides.");
            for (String kn : KNOWN) {
                ok |= kn.equals(key);
            }
            if (!ok) {
                throw new ConfigException("Clave de configuracion desconocida: '" + key + "'");
            }
        }
        model = str(m, "model", MODEL_SFM);
        if (!MODEL_SFM.equals(model)) {
            throw new ConfigException("Modelo no soportado: '" + model + "' (solo " + MODEL_SFM + ")");
        }
        eventId = (int) integer(m, "eventId", null);
        p1 = dbl(m, "p1", null);
        p2 = dbl(m, "p2", null);
        seed = integer(m, "seed", 0L);
        dt = dbl(m, "dt", 1e-4);
        outputPerFrame = (int) integer(m, "outputPerFrame", 3L);
        dataPath = str(m, "dataPath", "docs/elusion_1_contra_1.txt");
        readmePath = str(m, "readmePath", "docs/LEEME_elusion1contra1.txt");
        outputDir = str(m, "outputDir", "output");
        mass = dbl(m, "physicalParameters.mass", 80.0);
        tau = dbl(m, "physicalParameters.tau", 0.5);
        k = dbl(m, "physicalParameters.k", 1.2e5);
        kappa = dbl(m, "physicalParameters.kappa", 2.4e5);
        radius = dbl(m, "physicalParameters.radius", 0.25);
        velocityWindowSamples = (int) integer(m, "data.velocityWindowSamples", 8L);
        angleStepDeg = dbl(m, "avoidance.angleStepDeg", 1.0);
        avoidanceUpdateDt = dbl(m, "avoidance.updateDt", 0.01);
        arrivalTolerance = dbl(m, "avoidance.arrivalTolerance", 0.02);

        Map<String, String> ov = new TreeMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey().startsWith("idOverrides.")) {
                String rest = e.getKey().substring("idOverrides.".length());
                if (!rest.matches("\\d+\\.(left|right)")) {
                    throw new ConfigException("Clave idOverrides invalida '" + e.getKey() + "' (use idOverrides.<evento>.left|right)");
                }
                ov.put(rest, e.getValue().trim());
            }
        }
        idOverrides = ov;
        validate();
    }

    private void validate() {
        require(eventId >= 1, "eventId debe ser >= 1");
        require(isFinite(p1) && p1 > 0, "p1 (horizonte, s) debe ser > 0");
        require(isFinite(p2) && p2 > 0, "p2 (distancia de paso, m) debe ser > 0");
        require(isFinite(dt) && dt > 0 && dt <= 1e-2, "dt debe estar en (0, 0.01] s para integrar el contacto granular");
        require(outputPerFrame >= 1, "outputPerFrame debe ser >= 1");
        require(mass > 0 && tau > 0 && k > 0 && kappa >= 0 && radius > 0, "parametros fisicos deben ser positivos");
        require(velocityWindowSamples >= 2, "data.velocityWindowSamples debe ser >= 2");
        require(angleStepDeg > 0 && angleStepDeg <= 45, "avoidance.angleStepDeg en (0, 45]");
        require(avoidanceUpdateDt >= dt, "avoidance.updateDt debe ser >= dt");
        require(arrivalTolerance >= 0, "avoidance.arrivalTolerance debe ser >= 0");
        require(!dataPath.isEmpty() && !readmePath.isEmpty() && !outputDir.isEmpty(), "rutas vacias");
    }

    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static void require(boolean c, String msg) {
        if (!c) {
            throw new ConfigException(msg);
        }
    }

    // ---------------------------------------------------------------- carga

    public static SimulationConfig fromFile(Path file) {
        return fromMap(readFlat(file));
    }

    public static SimulationConfig fromMap(Map<String, String> flat) {
        return new SimulationConfig(flat);
    }

    /** Devuelve una copia con otro evento y otro (p1, p2); usado por el modo batch. */
    public SimulationConfig with(int newEvent, double newP1, double newP2) {
        Map<String, String> m = toFlat();
        m.put("eventId", Integer.toString(newEvent));
        m.put("p1", Double.toString(newP1));
        m.put("p2", Double.toString(newP2));
        return new SimulationConfig(m);
    }

    public static Map<String, String> readFlat(Path file) {
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("No se pudo leer la configuracion '" + file + "': " + e.getMessage());
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".json")) {
                return Json.flatten(Json.parse(text));
            }
            return parseTxt(text);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Configuracion invalida '" + file + "': " + e.getMessage());
        }
    }

    public static Map<String, String> parseTxt(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        int ln = 0;
        for (String raw : text.split("\\R")) {
            ln++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("linea " + ln + ": se esperaba 'clave = valor'");
            }
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            int hash = val.indexOf(" #");
            if (hash >= 0) {
                val = val.substring(0, hash).trim();
            }
            if (m.put(key, val) != null) {
                throw new IllegalArgumentException("linea " + ln + ": clave duplicada '" + key + "'");
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- utilidades

    /** Mapa aplanado con los valores efectivos (incluye defaults). */
    public Map<String, String> toFlat() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("eventId", Integer.toString(eventId));
        m.put("p1", Double.toString(p1));
        m.put("p2", Double.toString(p2));
        m.put("seed", Long.toString(seed));
        m.put("dt", Double.toString(dt));
        m.put("outputPerFrame", Integer.toString(outputPerFrame));
        m.put("dataPath", dataPath);
        m.put("readmePath", readmePath);
        m.put("outputDir", outputDir);
        m.put("physicalParameters.mass", Double.toString(mass));
        m.put("physicalParameters.tau", Double.toString(tau));
        m.put("physicalParameters.k", Double.toString(k));
        m.put("physicalParameters.kappa", Double.toString(kappa));
        m.put("physicalParameters.radius", Double.toString(radius));
        m.put("data.velocityWindowSamples", Integer.toString(velocityWindowSamples));
        m.put("avoidance.angleStepDeg", Double.toString(angleStepDeg));
        m.put("avoidance.updateDt", Double.toString(avoidanceUpdateDt));
        m.put("avoidance.arrivalTolerance", Double.toString(arrivalTolerance));
        for (Map.Entry<String, String> e : idOverrides.entrySet()) {
            m.put("idOverrides." + e.getKey(), e.getValue());
        }
        return m;
    }

    /** Cadena canonica (claves ordenadas): dos configuraciones equivalentes dan la misma cadena. */
    public String canonical() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(toFlat()).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String str(Map<String, String> m, String k, String def) {
        String v = m.get(k);
        if (v == null) {
            if (def == null) {
                throw new ConfigException("Falta el parametro requerido '" + k + "'");
            }
            return def;
        }
        return v;
    }

    private static double dbl(Map<String, String> m, String k, Double def) {
        String v = m.get(k);
        if (v == null) {
            if (def == null) {
                throw new ConfigException("Falta el parametro requerido '" + k + "'");
            }
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("Valor no numerico para '" + k + "': '" + v + "'");
        }
    }

    private static long integer(Map<String, String> m, String k, Long def) {
        String v = m.get(k);
        if (v == null) {
            if (def == null) {
                throw new ConfigException("Falta el parametro requerido '" + k + "'");
            }
            return def;
        }
        try {
            double d = Double.parseDouble(v.trim());
            if (d != Math.rint(d)) {
                throw new NumberFormatException();
            }
            return (long) d;
        } catch (NumberFormatException e) {
            throw new ConfigException("Valor entero invalido para '" + k + "': '" + v + "'");
        }
    }

    /** Error de configuracion o de entrada del usuario. */
    public static final class ConfigException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConfigException(String msg) {
            super(msg);
        }
    }
}
