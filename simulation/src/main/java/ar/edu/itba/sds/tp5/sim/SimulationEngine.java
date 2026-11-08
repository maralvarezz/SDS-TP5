package ar.edu.itba.sds.tp5.sim;

import ar.edu.itba.sds.tp5.avoidance.AvoidanceLayer;
import ar.edu.itba.sds.tp5.config.SimulationConfig;
import ar.edu.itba.sds.tp5.data.Event;
import ar.edu.itba.sds.tp5.model.Agent;
import ar.edu.itba.sds.tp5.model.SfmModel;

/**
 * Integrador de un evento (2 agentes). Velocity Verlet con paso fijo dt_eff, donde
 * dt_eff = (1/fps)/nSub con nSub entero (multiplo de outputPerFrame): cada instante experimental t_k
 * cae EXACTAMENTE en un paso de integracion y en una salida, sin interpolar.
 * Las fuerzas dependen de la velocidad (tendencia al target y friccion tangencial), por lo que
 * v(t+dt) se predice con v + a dt para evaluar a(t+dt) (esquema estandar de Verlet con fuerzas disipativas).
 */
public final class SimulationEngine {

    /** Receptor de los estados guardados (cada dt2 = dt_eff * stride). */
    public interface StateSink {
        void state(double timeS, double frameExp, Agent[] agents);
    }

    /** Resumen de la ejecucion. */
    public static final class Result {
        public int nSub;
        public double dtEff;
        public int outputStride;
        public long steps;
        public int outputs;
        public double durationS;
        public double fractionTimeAvoiding;
        public double maxAbsThetaRad;
        public long wallNanos;
    }

    private SimulationEngine() { }

    /** Subpasos de integracion por intervalo experimental (entero, multiplo de outputPerFrame). */
    public static int subSteps(SimulationConfig cfg, double fps) {
        return (int) Math.ceil((1.0 / fps) / cfg.dt / cfg.outputPerFrame) * cfg.outputPerFrame;
    }

    public static Agent[] initialAgents(SimulationConfig cfg, Event ev) {
        Event.AgentSpec[] specs = ev.agents();
        Agent[] ag = new Agent[specs.length];
        for (int i = 0; i < specs.length; i++) {
            Event.AgentSpec s = specs[i];
            ag[i] = new Agent(s.id, cfg.mass, cfg.radius, s.vd, s.targetX, s.targetY, s.x0, s.y0, s.vx0, s.vy0);
        }
        return ag;
    }

    /** Todos los agentes a menos de 'tol' (o 2 cm si tol = 0) de su target final y casi quietos (< 5 cm/s). */
    static boolean allSettled(Agent[] ag, double tol) {
        double r = Math.max(tol, 0.02);
        for (Agent a : ag) {
            if (Math.hypot(a.x - a.finalX, a.y - a.finalY) > r || Math.hypot(a.vx, a.vy) > 0.05) {
                return false;
            }
        }
        return true;
    }

    public static Result run(SimulationConfig cfg, Event ev, StateSink sink) {
        long t0 = System.nanoTime();
        Result res = new Result();
        double dtExp = 1.0 / ev.fps;
        int q = cfg.outputPerFrame;
        int nSub = subSteps(cfg, ev.fps);
        double dt = dtExp / nSub;
        int outStride = nSub / q;
        int updStride = Math.max(1, (int) Math.round(cfg.avoidanceUpdateDt / dt));
        long total = (long) (ev.endFrame - ev.startFrame) * nSub;       // fin del evento experimental
        long maxTotal = total + (cfg.extendUntilArrival ? (long) Math.ceil(cfg.maxExtraSeconds / dt) : 0L);
        long holdSteps = (long) Math.ceil(cfg.holdSeconds / dt);
        long arrivedSince = -1;

        Agent[] ag = initialAgents(cfg, ev);
        int n = ag.length;
        SfmModel model = new SfmModel(cfg.tau, cfg.k, cfg.kappa, cfg.arrivalTolerance);
        AvoidanceLayer layer = cfg.avoidanceEnabled ? new AvoidanceLayer(cfg.amplitude, cfg.spatialRange) : null;

        double[] ax = new double[n];
        double[] ay = new double[n];
        double[] bx = new double[n];
        double[] by = new double[n];
        double[] ovx = new double[n];
        double[] ovy = new double[n];

        long avoidSteps = 0;
        double maxTheta = 0;
        int outputs = 0;

        for (long step = 0; ; step++) {
            boolean doUpdate = step % updStride == 0;
            if (doUpdate) {
                if (layer != null) {
                    for (int i = 0; i < n; i++) {
                        layer.update(ag[i], ag, cfg.arrivalTolerance);
                    }
                }
                model.accelerations(ag, ax, ay);
            } else if (step == 0) {
                model.accelerations(ag, ax, ay);
            }
            if (step % outStride == 0) {
                double time = step * dt;
                sink.state(time, ev.startFrame + (double) step / nSub, ag);
                outputs++;
            }
            if (step >= total) {
                if (!cfg.extendUntilArrival || step >= maxTotal) {
                    total = step;
                    break;
                }
                // extension de animacion: ambos agentes en su target final y quietos, luego holdSeconds mas
                if (allSettled(ag, cfg.arrivalTolerance)) {
                    if (arrivedSince < 0) {
                        arrivedSince = step;
                    }
                    if (step - arrivedSince >= holdSteps) {
                        total = step;
                        break;
                    }
                } else {
                    arrivedSince = -1;
                }
            }
            for (int i = 0; i < n; i++) {
                if (ag[i].hasTemporalTarget) {
                    avoidSteps++;
                    maxTheta = Math.max(maxTheta, Math.abs(ag[i].theta));
                }
            }
            // Velocity Verlet
            for (int i = 0; i < n; i++) {
                Agent a = ag[i];
                ovx[i] = a.vx;
                ovy[i] = a.vy;
                a.x += a.vx * dt + 0.5 * ax[i] * dt * dt;
                a.y += a.vy * dt + 0.5 * ay[i] * dt * dt;
                a.vx += ax[i] * dt;
                a.vy += ay[i] * dt;
            }
            model.accelerations(ag, bx, by);
            for (int i = 0; i < n; i++) {
                Agent a = ag[i];
                a.vx = ovx[i] + 0.5 * (ax[i] + bx[i]) * dt;
                a.vy = ovy[i] + 0.5 * (ay[i] + by[i]) * dt;
                ax[i] = bx[i];
                ay[i] = by[i];
            }
        }
        res.nSub = nSub;
        res.dtEff = dt;
        res.outputStride = outStride;
        res.steps = total;
        res.outputs = outputs;
        res.durationS = total * dt;
        res.fractionTimeAvoiding = total == 0 ? 0 : (double) avoidSteps / (total * n);
        res.maxAbsThetaRad = maxTheta;
        res.wallNanos = System.nanoTime() - t0;
        return res;
    }
}
