package ar.edu.itba.sds.tp5.model;

/** Peaton del SFM: estado dinamico, parametros propios y target(s). */
public final class Agent {
    public final String id;
    public final double mass;
    public final double radius;
    /** Modulo de la velocidad deseada (rapidez media experimental). */
    public final double vd;
    /** Target final (ultima posicion experimental). */
    public final double finalX;
    public final double finalY;

    public double x;
    public double y;
    public double vx;
    public double vy;

    /** Target temporal de la capa de elusion; solo valido si {@link #hasTemporalTarget}. */
    public boolean hasTemporalTarget;
    public double tempX;
    public double tempY;
    /** Desvio angular (rad) aplicado por la capa de elusion; 0 si no hay conflicto. Solo informativo/diagnostico. */
    public double theta;

    public Agent(String id, double mass, double radius, double vd, double finalX, double finalY,
                 double x, double y, double vx, double vy) {
        this.id = id;
        this.mass = mass;
        this.radius = radius;
        this.vd = vd;
        this.finalX = finalX;
        this.finalY = finalY;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
    }

    /** Target vigente: el temporal si la capa de elusion lo definio, si no el final. */
    public double targetX() {
        return hasTemporalTarget ? tempX : finalX;
    }

    public double targetY() {
        return hasTemporalTarget ? tempY : finalY;
    }
}
