package ar.edu.itba.sds.tp5.avoidance;

import ar.edu.itba.sds.tp5.model.Agent;

/**
 * Capa de elusion (Problema 1). Dos parametros ajustables:
 *   p1 = T  : horizonte de anticipacion [s]
 *   p2 = D  : distancia de paso de seguridad centro-a-centro [m]
 *
 * Actua UNICAMENTE reemplazando el target final por un target temporal (cambia la direccion de la
 * velocidad deseada); no agrega fuerzas ni toca las reglas del SFM. Para el agente i y otro agente j:
 *
 *  1. Para una direccion candidata u = vd_i * e, la posicion relativa de j es  s(t) = dr + (v_j - u) t,
 *     con dr = r_j - r_i. Si el movimiento relativo es de acercamiento (t* = -dr.w/|w|^2 > 0, w = v_j - u),
 *     la distancia minima prevista en [0, T] es  d(u) = |dr + w min(t*, T)|.
 *     Hay CONFLICTO si d(u) < D. (Si se alejan, t* <= 0, no hay conflicto.)
 *  2. Si no hay conflicto con e_f (direccion al target final) -> sin target temporal.
 *  3. Si lo hay, se busca el menor giro |theta| (en pasos de angleStep, alternando lados) de e_f tal que la
 *     direccion rotada R(theta) e_f NO tenga conflicto. A igual |theta| se prefiere el lado opuesto a j
 *     (j a la izquierda de e_f -> giro horario); si j esta alineado, horario ("keep right").
 *  4. Target temporal:  P_t = r_i + L R(theta) e_f,  con L = |P_final - r_i|; el SFM apunta e hacia P_t.
 *  5. La condicion se reevalua cada 'updateDt'. Cuando e_f deja de tener conflicto (theta = 0)
 *     el agente retoma su target final. theta(t) es continuo en el borde del conflicto, sin umbrales
 *     adicionales, por lo que no hay oscilacion conectado/desconectado.
 *
 * Si ninguna direccion resuelve el conflicto se elige la de mayor distancia minima prevista.
 * Con mas de un vecino se resuelve el giro mas chico que satisface a TODOS (aqui el evento tiene un solo vecino).
 */
public final class AvoidanceLayer {
    private final double horizon;
    private final double clearance;
    private final double stepRad;

    public AvoidanceLayer(double p1Horizon, double p2Clearance, double angleStepDeg) {
        this.horizon = p1Horizon;
        this.clearance = p2Clearance;
        this.stepRad = Math.toRadians(angleStepDeg);
    }

    /** Distancia minima prevista en [0,T] si hay acercamiento; +inf si se alejan o no hay movimiento relativo. */
    double predictedClearance(double dx, double dy, double wx, double wy) {
        double w2 = wx * wx + wy * wy;
        if (w2 < 1e-12) {
            return Double.POSITIVE_INFINITY;
        }
        double tc = -(dx * wx + dy * wy) / w2;
        if (tc <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (tc > horizon) {
            tc = horizon;
        }
        return Math.hypot(dx + wx * tc, dy + wy * tc);
    }

    /**
     * Actualiza el target temporal de {@code self} frente a {@code others} (estado actual, vecinos suponen velocidad constante).
     */
    public void update(Agent self, Agent[] others, double arrivalTol) {
        double fx = self.finalX - self.x;
        double fy = self.finalY - self.y;
        double L = Math.hypot(fx, fy);
        if (L <= arrivalTol || L == 0) {
            clear(self);
            return;
        }
        double ex = fx / L;
        double ey = fy / L;
        if (!conflict(self, others, ex, ey)) {
            clear(self);
            return;
        }
        double best = Double.NaN;
        boolean found = false;
        double fallbackTheta = 0;
        double fallbackScore = -1;
        int nMax = (int) Math.floor(Math.PI / stepRad);
        double pref = preferredSign(self, others, ex, ey);
        for (int k = 1; k <= nMax && !found; k++) {
            for (int s = 0; s < 2 && !found; s++) {
                double th = (s == 0 ? pref : -pref) * k * stepRad;
                double c = Math.cos(th);
                double sn = Math.sin(th);
                double cx = c * ex - sn * ey;
                double cy = sn * ex + c * ey;
                double score = minClearance(self, others, cx, cy);
                if (score >= clearance) {
                    best = th;
                    found = true;
                } else if (score > fallbackScore) {
                    fallbackScore = score;
                    fallbackTheta = th;
                }
            }
        }
        double theta = found ? best : fallbackTheta;
        double c = Math.cos(theta);
        double sn = Math.sin(theta);
        self.theta = theta;
        self.hasTemporalTarget = true;
        self.tempX = self.x + L * (c * ex - sn * ey);
        self.tempY = self.y + L * (sn * ex + c * ey);
    }

    private static void clear(Agent a) {
        a.theta = 0;
        a.hasTemporalTarget = false;
    }

    private boolean conflict(Agent self, Agent[] others, double ex, double ey) {
        return minClearance(self, others, ex, ey) < clearance;
    }

    private double minClearance(Agent self, Agent[] others, double ex, double ey) {
        double ux = self.vd * ex;
        double uy = self.vd * ey;
        double m = Double.POSITIVE_INFINITY;
        for (Agent o : others) {
            if (o == self) {
                continue;
            }
            m = Math.min(m, predictedClearance(o.x - self.x, o.y - self.y, o.vx - ux, o.vy - uy));
        }
        return m;
    }

    /** +1 (antihorario) o -1 (horario): lado preferido para esquivar al vecino mas comprometido. */
    private double preferredSign(Agent self, Agent[] others, double ex, double ey) {
        double worst = Double.POSITIVE_INFINITY;
        double cross = 0;
        for (Agent o : others) {
            if (o == self) {
                continue;
            }
            double sc = predictedClearance(o.x - self.x, o.y - self.y, o.vx - self.vd * ex, o.vy - self.vd * ey);
            if (sc < worst) {
                worst = sc;
                cross = ex * (o.y - self.y) - ey * (o.x - self.x);
            }
        }
        // vecino a la izquierda (cross > 0) -> giro horario (-1); alineado -> horario
        return cross > 1e-9 ? -1.0 : (cross < -1e-9 ? 1.0 : -1.0);
    }
}
