package ar.edu.itba.sds.tp5.model;

/**
 * Social Force Model SIN fuerza social (Helbing, Farkas & Vicsek 2000), es decir:
 *
 *   m_i dv_i/dt = m_i (vd_i e_i - v_i)/tau  +  sum_j f_ij^contacto
 *   f_ij = k g(r_ij - d_ij) n_ij + kappa g(r_ij - d_ij) (dv_ji . t_ij) t_ij
 *
 * con g(x) = max(x, 0), n_ij = (r_i - r_j)/d_ij, t_ij = (-n_y, n_x), dv_ji = v_j - v_i.
 * No hay termino A exp[(r_ij - d_ij)/B]. e_i apunta al target vigente (final o temporal).
 * Esta clase NO conoce la capa de elusion: solo lee el target vigente del agente.
 */
public final class SfmModel {
    private final double tau;
    private final double k;
    private final double kappa;
    private final double arrivalTol;

    public SfmModel(double tau, double k, double kappa, double arrivalTolerance) {
        this.tau = tau;
        this.k = k;
        this.kappa = kappa;
        this.arrivalTol = arrivalTolerance;
    }

    /** Calcula las aceleraciones de todos los agentes con el estado actual (x, y, vx, vy). */
    public void accelerations(Agent[] a, double[] ax, double[] ay) {
        int n = a.length;
        for (int i = 0; i < n; i++) {
            Agent p = a[i];
            double dx = p.targetX() - p.x;
            double dy = p.targetY() - p.y;
            double d = Math.hypot(dx, dy);
            double ex = 0;
            double ey = 0;
            if (d > arrivalTol && d > 0) {
                ex = dx / d;
                ey = dy / d;
            }
            ax[i] = (p.vd * ex - p.vx) / tau;
            ay[i] = (p.vd * ey - p.vy) / tau;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Agent p = a[i];
                Agent q = a[j];
                double rx = p.x - q.x;
                double ry = p.y - q.y;
                double d = Math.hypot(rx, ry);
                double overlap = p.radius + q.radius - d;
                if (overlap <= 0 || d == 0) {
                    continue;
                }
                double nx = rx / d;
                double ny = ry / d;
                double tx = -ny;
                double ty = nx;
                double dvt = (q.vx - p.vx) * tx + (q.vy - p.vy) * ty;
                double fn = k * overlap;
                double ft = kappa * overlap * dvt;
                double fx = fn * nx + ft * tx;
                double fy = fn * ny + ft * ty;
                ax[i] += fx / p.mass;
                ay[i] += fy / p.mass;
                ax[j] -= fx / q.mass;
                ay[j] -= fy / q.mass;
            }
        }
    }
}
