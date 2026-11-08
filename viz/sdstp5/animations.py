"""Animaciones offline: trayectorias experimentales vs simuladas a partir de los TXT (no ejecuta el motor)."""
from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib import animation
from matplotlib.patches import Circle

COL = [("#0072B2", "#56B4E9"), ("#D55E00", "#E69F00")]  # (simulado, experimental) por peaton


def animate_event(event, run, out_file: Path, radius=0.25, fmt="mp4"):
    """Un cuadro por frame experimental (tiempo real a fps del dataset). Circulos = simulado (con su radio);
    puntos = experimental (se omite en los frames sin dato; sin interpolar). Borde rojo = capa de elusion activa."""
    out_file = Path(out_file)
    out_file.parent.mkdir(parents=True, exist_ok=True)
    ids = list(event.agents)
    # la corrida puede extenderse DESPUES del ultimo instante experimental (hasta que ambos llegan al target + espera):
    # en esos cuadros el dato experimental queda en su ultima posicion
    last_frame = int(max(event.end_frame, np.floor(max(run.agents[a].frame[-1] for a in ids) + 1e-6)))
    frames = np.arange(event.start_frame, last_frame + 1)
    sim_idx = {}
    for aid in ids:
        sm = run.agents[aid]
        sim_idx[aid] = np.searchsorted(sm.frame, frames.astype(float) - 1e-3)
    allx = np.concatenate([event.agents[a].x for a in ids] + [run.agents[a].x for a in ids])
    ally = np.concatenate([event.agents[a].y for a in ids] + [run.agents[a].y for a in ids])
    fig, ax = plt.subplots(figsize=(10, 4.8), constrained_layout=True)
    ax.set_xlim(allx.min() - 0.5, allx.max() + 0.5)
    ax.set_ylim(ally.min() - 0.5, ally.max() + 0.5)
    ax.set_aspect("equal")
    ax.set_xlabel("x [m]")
    ax.set_ylabel("y [m]")
    ax.grid(alpha=0.3)
    circles, exp_pts, trails_s, trails_e = [], [], [], []
    for k, aid in enumerate(ids):
        c = Circle((0, 0), radius, fc=COL[k][0], alpha=0.55, ec=COL[k][0], lw=2)
        ax.add_patch(c)
        circles.append(c)
        (p,) = ax.plot([], [], "o", ms=7, color=COL[k][1], mec="k", label=f"experimental {aid}")
        exp_pts.append(p)
        (ts,) = ax.plot([], [], "-", lw=1.5, color=COL[k][0], label=f"simulado {aid}")
        trails_s.append(ts)
        (te,) = ax.plot([], [], ":", lw=1.2, color="k")
        trails_e.append(te)
        tx, ty = run.agents[aid].meta["target_x"], run.agents[aid].meta["target_y"]
        ax.plot(tx, ty, "*", color=COL[k][0], ms=12, mec="k")
    title = ax.set_title("")
    ax.legend(loc="upper center", ncol=4, fontsize=8, bbox_to_anchor=(0.5, -0.30))
    fps = event.fps

    def update(i):
        f = frames[i]
        for k, aid in enumerate(ids):
            sm, ex = run.agents[aid], event.agents[aid]
            j = min(sim_idx[aid][i], len(sm.x) - 1)
            circles[k].center = (sm.x[j], sm.y[j])
            avoid = sm.theta[j] != 0
            circles[k].set_edgecolor("red" if avoid else COL[k][0])
            trails_s[k].set_data(sm.x[: j + 1], sm.y[: j + 1])
            m = ex.frame <= f
            have = np.where(ex.frame == f)[0] if f <= event.end_frame else [len(ex.x) - 1]
            exp_pts[k].set_data(ex.x[have], ex.y[have])
            trails_e[k].set_data(ex.x[m], ex.y[m])
        title.set_text(f"Evento {event.event_id}  t = {(f - event.start_frame) / fps:5.2f} s   "
                       f"A = {run.A:g} m, λ = {run.lam:g} m   (borde rojo = elusión activa)")
        return circles + exp_pts + trails_s + trails_e + [title]

    anim = animation.FuncAnimation(fig, update, frames=len(frames), interval=1000 / fps, blit=False)
    if fmt == "mp4" and animation.writers.is_available("ffmpeg"):
        anim.save(out_file.with_suffix(".mp4"), writer=animation.FFMpegWriter(fps=fps, bitrate=1800))
        res = out_file.with_suffix(".mp4")
    else:
        anim.save(out_file.with_suffix(".gif"), writer=animation.PillowWriter(fps=min(fps, 15)))
        res = out_file.with_suffix(".gif")
    plt.close(fig)
    return res
