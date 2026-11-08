"""Genera input/event_windows.json: ventana LIMPIA de cada evento (decision de limpieza de datos, ver README).

Lee los eventos crudos exportados por el motor SIN ventanas (output/events_raw, ventana completa del LEEME) y aplica:
  inicio : un peaton que arranca por debajo del carril (y0 < 1 m) tiene un tramo vertical inicial que no es parte
           del cruce; su inicio es el primer instante en que llega al carril central (|y - 2 m| < tol; tol se agranda
           de a poco si llega "de costado").  Los demas arrancan en su primer cuadro.
  final  : instante de maxima cercania al punto donde (ya recortado) empezo el otro peaton.
  ventana comun del evento = INTERSECCION de las dos ventanas individuales (inicio mas tardio, final mas temprano),
           porque la simulacion y los dos errores necesitan un eje de tiempo comun.
Excepciones revisadas a mano evento por evento (MANUAL, con motivo):
  18/26 : el peaton sube al principio; arranca realmente cuando termina de subir (cuadro 17).
  13    : las trayectorias terminan antes de llegar al inicio del otro; final = ultimo cuadro e inicio = punto mas
          cercano al final del otro peaton.
  1     : al peaton 36 le faltan 32 cuadros (4.26-6.46 s); ambos terminan en el ultimo cuadro anterior al hueco
          (no se inventan datos).

Uso:  python3 viz/make_event_windows.py [--plot DIR]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "viz"))
from sdstp5.readers import read_events_dir  # noqa: E402

START_IDX = {(18, "26"): 17, (13, "15"): 76, (13, "35"): 81}   # indice dentro de la serie del peaton (crudo)
END_LAST = {13}
END_IDX = {(1, "3"): 64, (1, "36"): 64}
MANUAL_NOTES = {
    1: "hueco de 32 cuadros del peaton 36 (4.26-6.46 s): ambos terminan antes del hueco; no se inventan datos",
    13: "las trayectorias terminan antes de llegar al inicio del otro: final = ultimo cuadro, inicio = cercano al final del otro",
    18: "peaton 26: sube al principio; arranca cuando termina de subir (cuadro 17)",
}


def lane_entry(t) -> int:
    if t.y[0] >= 1.0:
        return 0
    tol = 0.15
    k = 0
    while tol < 0.6:
        k = int(np.argmax(np.abs(t.y - 2.0) < tol))
        if abs(t.x[k] - t.x[0]) < 1.0:
            return k
        tol += 0.05
    return k


def individual_cuts(events: dict) -> dict:
    cuts = {}
    for e in sorted(events):
        (a, ta), (b, tb) = list(events[e].agents.items())
        trk = {a: ta, b: tb}
        k0 = {p: START_IDX.get((e, p), lane_entry(trk[p])) for p in (a, b)}
        for p, q in ((a, b), (b, a)):
            sx, sy = trk[q].x[k0[q]], trk[q].y[k0[q]]
            d = np.hypot(trk[p].x - sx, trk[p].y - sy)
            d[: k0[p]] = np.inf
            kend = END_IDX.get((e, p), len(trk[p].x) - 1 if e in END_LAST else int(np.argmin(d)))
            cuts[(e, p)] = (k0[p], kend)
    return cuts


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--events", default=str(ROOT / "output/events_raw"))
    ap.add_argument("--out", default=str(ROOT / "input/event_windows.json"))
    ap.add_argument("--plot", default=None, help="directorio para las hojas de contacto de las trayectorias limpias")
    args = ap.parse_args()
    events = read_events_dir(Path(args.events))
    cuts = individual_cuts(events)
    windows = {}
    for e in sorted(events):
        ids = list(events[e].agents)
        fr = {p: (int(events[e].agents[p].frame[cuts[(e, p)][0]]), int(events[e].agents[p].frame[cuts[(e, p)][1]])) for p in ids}
        start = max(f[0] for f in fr.values())
        end = min(f[1] for f in fr.values())
        fps = events[e].fps
        windows[str(e)] = {
            "startFrame": start, "endFrame": end,
            "duration_s": round((end - start) / fps, 4),
            "individual": {p: {"startFrame": fr[p][0], "endFrame": fr[p][1]} for p in ids},
            "note": MANUAL_NOTES.get(e, "regla automatica (ver cabecera de make_event_windows.py)"),
        }
        print(f"evento {e:2d}: frames {start}..{end}  ({(end - start) / fps:.2f} s)")
    Path(args.out).write_text(json.dumps({
        "description": "Ventanas LIMPIAS por evento (interseccion de las ventanas individuales). Generado por "
                       "viz/make_event_windows.py; decision de limpieza de datos que reemplaza la ventana del LEEME.",
        "windows": windows}, indent=1) + "\n")
    print("escrito", args.out)

    if args.plot:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        out = Path(args.plot)
        out.mkdir(parents=True, exist_ok=True)
        col = ["#0072B2", "#D55E00"]
        ids = sorted(events)
        for k, chunk in enumerate([ids[:10], ids[10:]], 1):
            fig, axs = plt.subplots(5, 2, figsize=(16, 12.5))
            for ax, e in zip(axs.ravel(), chunk):
                w = windows[str(e)]
                for i, (p, t) in enumerate(events[e].agents.items()):
                    keep = (t.frame >= w["startFrame"]) & (t.frame <= w["endFrame"])
                    ax.plot(t.x, t.y, "o", ms=2.2, color="#bdbdbd", zorder=1)
                    ax.plot(t.x[keep], t.y[keep], "o", ms=3.0, color=col[i], zorder=2)
                    ax.plot(t.x[keep][0], t.y[keep][0], "s", color=col[i], mec="k", ms=7, zorder=3)
                    ax.plot(t.x[keep][-1], t.y[keep][-1], "*", color=col[i], mec="k", ms=12, zorder=3)
                ax.set_aspect("equal", adjustable="datalim")
                ax.set_title(f"Evento {e}  (ventana común {w['duration_s']:.2f} s)", fontsize=9)
                ax.set_xlabel("x [m]", fontsize=8)
                ax.set_ylabel("y [m]", fontsize=8)
            for ax in axs.ravel()[len(chunk):]:
                ax.axis("off")
            fig.suptitle("Trayectorias experimentales limpias: gris = se saca, color = queda (■ inicio, ★ fin)", fontsize=11)
            fig.tight_layout()
            fig.savefig(out / f"trayectorias_limpias_{k}.png", bbox_inches="tight", dpi=150)
            plt.close(fig)


if __name__ == "__main__":
    main()
