package ar.edu.itba.sds.tp5.data;

import java.util.List;

/** Evento: dos peatones, ventana temporal comun, y condiciones iniciales/finales derivadas de los datos. */
public final class Event {

    /** Un peaton del evento con su trayectoria experimental dentro de la ventana y su configuracion inicial. */
    public static final class AgentSpec {
        public final String id;
        public final String side;     // "left" | "right"
        public final int[] frames;    // frames experimentales presentes en la ventana (pueden tener huecos)
        public final double[] x;
        public final double[] y;
        public final double x0;
        public final double y0;
        public final double vx0;
        public final double vy0;
        public final double vd;       // rapidez media experimental [m/s]
        public final double targetX;  // ultima posicion experimental
        public final double targetY;
        public final int missingFrames; // frames sin dato dentro de la ventana

        public AgentSpec(String id, String side, int[] frames, double[] x, double[] y, double vx0, double vy0,
                         double vd, int missingFrames) {
            this.id = id;
            this.side = side;
            this.frames = frames;
            this.x = x;
            this.y = y;
            this.x0 = x[0];
            this.y0 = y[0];
            this.vx0 = vx0;
            this.vy0 = vy0;
            this.vd = vd;
            this.targetX = x[x.length - 1];
            this.targetY = y[y.length - 1];
            this.missingFrames = missingFrames;
        }
    }

    public final int eventId;
    public final Passage passage;
    public final double fps;
    public final int startFrame;
    public final int endFrame;
    public final AgentSpec left;
    public final AgentSpec right;
    public final double minDistanceRecomputed;
    public final List<String> warnings;

    public Event(int eventId, Passage passage, double fps, int startFrame, int endFrame, AgentSpec left,
                 AgentSpec right, double minDistanceRecomputed, List<String> warnings) {
        this.eventId = eventId;
        this.passage = passage;
        this.fps = fps;
        this.startFrame = startFrame;
        this.endFrame = endFrame;
        this.left = left;
        this.right = right;
        this.minDistanceRecomputed = minDistanceRecomputed;
        this.warnings = warnings;
    }

    public AgentSpec[] agents() {
        return new AgentSpec[] {left, right};
    }

    /** Duracion fisica del evento [s]. */
    public double duration() {
        return (endFrame - startFrame) / fps;
    }
}
