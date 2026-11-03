package ar.edu.itba.sds.tp5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Datos sinteticos para pruebas: dataset + LEEME minimos dentro de un directorio raiz temporal. */
public final class TestSupport {
    private TestSupport() { }

    public static final double FPS = 15.0;

    /** Escribe docs/data.txt y docs/leeme.txt con un cruce frontal: izq id 1 (+x), der id 2 (-x). */
    public static void writeHeadOn(Path root, double yLeft, double yRight, double speed, boolean ambiguousLeft) throws IOException {
        Files.createDirectories(root.resolve("docs"));
        StringBuilder sb = new StringBuilder("# framerate: 15.0 fps\n# id frame x/m y/m markerID\n");
        for (int f = 100; f <= 250; f++) {
            double t = (f - 100) / FPS;
            sb.append(String.format(Locale.ROOT, "1 %d %.5f %.5f 1\n", f, 0.5 + speed * t, yLeft));
            sb.append(String.format(Locale.ROOT, "2 %d %.5f %.5f 2\n", f, 9.5 - speed * t, yRight));
        }
        Files.write(root.resolve("docs/data.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
        String left = ambiguousLeft ? "V?" : "1";
        Files.write(root.resolve("docs/leeme.txt"), ("Personas: 2\nPruebas/pasadas: 1\n"
            + "  prueba   1: sale izquierda  " + left + ", sale derecha   2, cuadros 100-250, distancia minima 0.05 m\n")
            .getBytes(StandardCharsets.UTF_8));
    }

    public static String configJson(double p1, double p2) {
        return "{\"eventId\":1,\"p1\":" + p1 + ",\"p2\":" + p2 + ",\"dt\":0.0005,\"outputPerFrame\":3,"
            + "\"dataPath\":\"docs/data.txt\",\"readmePath\":\"docs/leeme.txt\",\"outputDir\":\"output\","
            + "\"idOverrides\":{\"1\":{\"left\":\"1\"}}}";
    }
}
