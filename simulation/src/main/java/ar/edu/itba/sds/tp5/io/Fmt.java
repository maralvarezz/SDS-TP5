package ar.edu.itba.sds.tp5.io;

import java.util.Locale;

/** Formateo numerico rapido y determinista (punto decimal, sin notacion cientifica). */
public final class Fmt {
    private Fmt() { }

    public static void fixed(StringBuilder sb, double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append("nan");
            return;
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        double r = Math.abs(v) * scale;
        if (r > 9e17) {
            sb.append(String.format(Locale.ROOT, "%." + decimals + "f", v));
            return;
        }
        long m = Math.round(r);
        if (v < 0 && m != 0) {
            sb.append('-');
        }
        long ip = m / scale;
        long fp = m % scale;
        sb.append(ip);
        if (decimals > 0) {
            sb.append('.');
            String f = Long.toString(fp);
            for (int i = f.length(); i < decimals; i++) {
                sb.append('0');
            }
            sb.append(f);
        }
    }

    public static String fixed(double v, int decimals) {
        StringBuilder sb = new StringBuilder();
        fixed(sb, v, decimals);
        return sb.toString();
    }
}
