package ar.edu.itba.sds.tp5.avoidance;

import ar.edu.itba.sds.tp5.model.Agent;

/**
 * Capa de elusion CONTINUA (Problema 1). Exactamente dos parametros calibrables:
 *   A      = amplitud de elusion [m]     (escala maxima del desplazamiento lateral del target temporal)
 *   lambda = alcance espacial [m]        (a que distancia se "siente" al otro peaton)
 *
 * Actua UNICAMENTE reemplazando el target final por un target temporal (cambia la direccion de la velocidad
 * deseada); no agrega fuerzas ni toca las reglas del SFM ni el modulo de la velocidad deseada.
 *
 * Para el agente i y el otro peaton j, con d = |r_j - r_i| (distancia ACTUAL entre centros):
 *
 *     a(d)    = A exp(-d / lambda)                       (offset lateral del target; NO es una separacion garantizada)
 *     P_temp  = r_i + L0 e_f + a(d) w(s) n               e_f = direccion unitaria al target final
 *                                                        n   = (e_y, -e_x) = derecha propia respecto del avance
 *                                                        w(s)= peso de "encuentro vigente" (ver abajo)
 *
 * No hay umbral binario de conflicto: para todo d finito y A > 0 el offset es positivo y varia de forma continua.
 * El angulo de desvio resultante es |theta| = atan( a w / L0 ).
 *
 * Constantes de DISENO (fijas, documentadas, NO calibrables; idénticas en todos los experimentos):
 *   L0       = 1.0 m : punto de avance sobre e_f. Es del orden de un paso largo; con A hasta ~2 m el giro maximo
 *                      es ~63 grados. Se usa un valor fijo (y no la distancia restante a la meta) para que el
 *                      angulo no dependa de cuanto falta para llegar.
 *   ELL      = L0    : longitud de la transicion de "encuentro terminado" (ver w(s)). Reusa L0 para no introducir
 *                      otra escala.
 *
 * Fin del encuentro (w): s = (r_j - r_i) . e_f es la proyeccion longitudinal del otro respecto del avance.
 *   s >= 0       -> el otro esta adelante: w = 1 (se conserva exactamente a(d) = A exp(-d/lambda)).
 *   -ELL < s < 0 -> el otro ya paso: w baja suavemente a 0 con smoothstep u^2 (3 - 2u), u = 1 + s/ELL
 *                   (continua y con derivada nula en s = 0 y s = -ELL, sin saltos de direccion).
 *   s <= -ELL    -> encuentro terminado: w = 0, el agente vuelve a apuntar al target final.
 * Es una regla geometrica determinista, sin parametros adicionales a calibrar.
 *
 * Con mas de un vecino se usa el de mayor offset efectivo a(d) w(s). Casos limite (sin vecino, d = 0, estados no
 * finitos, llegada al destino) no producen NaN/Infinity.
 */
public final class AvoidanceLayer {
    /** Punto de avance fijo sobre la direccion al destino [m] (constante de diseno, no calibrable). */
    public static final double LOOKAHEAD_L0 = 1.0;
    /** Longitud de la transicion de fin de encuentro [m] (constante de diseno, no calibrable). */
    public static final double TRANSITION_LENGTH = LOOKAHEAD_L0;

    private final double amplitude;
    private final double spatialRange;

    public AvoidanceLayer(double amplitude, double spatialRange) {
        if (!(Double.isFinite(amplitude) && amplitude > 0)) {
            throw new IllegalArgumentException("amplitude (A) debe ser finita y > 0: " + amplitude);
        }
        if (!(Double.isFinite(spatialRange) && spatialRange > 0)) {
            throw new IllegalArgumentException("spatialRange (lambda) debe ser finito y > 0: " + spatialRange);
        }
        this.amplitude = amplitude;
        this.spatialRange = spatialRange;
    }

    /** Offset base a(d) = A exp(-d / lambda), sin la logica de encuentro. */
    public double offset(double distance) {
        if (!Double.isFinite(distance) || distance < 0) {
            return 0.0;
        }
        return amplitude * Math.exp(-distance / spatialRange);
    }

    /**
     * Peso de encuentro vigente w(s) en [0, 1], continuo en s. Recibe la proyeccion longitudinal s del otro agente
     * sobre la direccion de avance.
     */
    public static double encounterWeight(double s) {
        if (!Double.isFinite(s) || s >= 0) {
            return Double.isFinite(s) ? 1.0 : 0.0;
        }
        if (s <= -TRANSITION_LENGTH) {
            return 0.0;
        }
        double u = 1.0 + s / TRANSITION_LENGTH;
        return u * u * (3.0 - 2.0 * u);
    }

    /** Actualiza el target temporal de {@code self} frente a {@code others} (estado actual). */
    public void update(Agent self, Agent[] others, double arrivalTol) {
        double fx = self.finalX - self.x;
        double fy = self.finalY - self.y;
        double remaining = Math.hypot(fx, fy);
        if (!Double.isFinite(remaining) || remaining <= arrivalTol || remaining == 0) {
            clear(self);
            return;
        }
        double ex = fx / remaining;
        double ey = fy / remaining;

        double best = 0.0;
        for (Agent o : others) {
            if (o == self) {
                continue;
            }
            double dx = o.x - self.x;
            double dy = o.y - self.y;
            double d = Math.hypot(dx, dy);
            if (!Double.isFinite(d)) {
                continue;
            }
            double a = offset(d) * encounterWeight(dx * ex + dy * ey);
            if (a > best) {
                best = a;
            }
        }
        if (best <= 0.0) {
            clear(self);
            return;
        }
        // derecha propia respecto del avance: n = (e_y, -e_x)
        double nx = ey;
        double ny = -ex;
        self.tempX = self.x + LOOKAHEAD_L0 * ex + best * nx;
        self.tempY = self.y + LOOKAHEAD_L0 * ey + best * ny;
        self.hasTemporalTarget = true;
        // angulo DERIVADO de la direccion efectiva (negativo = giro horario, hacia la derecha propia)
        self.theta = Math.atan2(-best, LOOKAHEAD_L0);
    }

    private static void clear(Agent a) {
        a.theta = 0;
        a.hasTemporalTarget = false;
    }
}
