"""Graficos del Problema 1. Solo leen tablas derivadas / TXT persistidos."""
from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker
import numpy as np

from .observables import sim_at_experimental_frames

# Paleta apta daltonismo (Okabe-Ito): una por metrica, la misma en todas las figuras.
C = {"E1": "#0072B2", "E2": "#D55E00", "exp": "#222222", "sim": "#0072B2", "grid": "#d9d9d9"}
LABEL = {"E1": r"$\langle E^{(1)}\rangle$ [m$^2$]", "E2": r"$\langle E^{(2)}\rangle$ [m$^2$]"}
NAME = {"E1": "Métrica 1 (iguales tiempos)", "E2": "Métrica 2 (punto más cercano)"}
P1L = "A: amplitud de elusión [m]"
P2L = "λ: alcance espacial [m]"


def _style():
    plt.rcParams.update({"figure.dpi": 130, "savefig.dpi": 200, "axes.spines.top": False, "axes.spines.right": False,
                         "axes.grid": True, "grid.color": C["grid"], "grid.linewidth": 0.6, "font.size": 10,
                         "axes.titlesize": 11, "legend.frameon": False})


def _save(fig, out: Path, name: str):
    out.mkdir(parents=True, exist_ok=True)
    fig.savefig(out / f"{name}.png", bbox_inches="tight")
    plt.close(fig)


def _edges(v):
    """Bordes de celda para una grilla uniforme (centros v)."""
    v = np.asarray(v, float)
    h = np.diff(v).mean()
    return np.concatenate([v - h / 2, [v[-1] + h / 2]])


def surface_heatmaps(pop, opt, out: Path, baseline=None, lam_max=None, region=None):
    """lam_max: solo recorta lo que se MUESTRA (la calibracion usa toda la grilla). region: {metrica: DataFrame A, lam}
    con el conjunto de indiferencia (se marca con puntos)."""
    _style()
    if lam_max is not None:
        pop = pop[pop.lam <= lam_max + 1e-9]
    A = np.sort(pop.A.unique())
    lam = np.sort(pop.lam.unique())
    fig, axs = plt.subplots(1, 2, figsize=(12.5, 5.4))
    for ax, m in zip(axs, ("E1", "E2")):
        Z = pop.pivot(index="A", columns="lam", values=m).loc[A, lam].values
        # ejes lineales y a escala: la grilla es uniforme en A y en lambda
        im = ax.pcolormesh(_edges(lam), _edges(A), Z, shading="flat", cmap="viridis_r")
        ax.grid(False)
        if region is not None and m in region:
            r = region[m][region[m].lam <= lam.max() + 1e-9]
            ax.plot(r.lam, r.A, ".", ms=4, color="k", alpha=0.7, label=f"dentro de 1 error estándar del óptimo ({len(r)} pares)")
        ax.plot(opt[m]["lam"], opt[m]["A"], marker="o", ms=8, color="white", mec="black", mew=1.5, ls="",
                label="óptimo")
        ax.set_xlabel(P2L)
        ax.set_ylabel(P1L)
        ax.set_title(NAME[m])
        ax.legend(loc="upper left", fontsize=10, frameon=True, framealpha=0.6, facecolor="white", edgecolor="none")
        cb = fig.colorbar(im, ax=ax)
        cb.set_label(LABEL[m])
    fig.suptitle("Calibración poblacional: error en la grilla uniforme (A, λ)")
    fig.tight_layout()
    _save(fig, out, "poblacional_superficie_heatmap")

    fig = plt.figure(figsize=(12, 5))
    for k, m in enumerate(("E1", "E2")):
        ax = fig.add_subplot(1, 2, k + 1, projection="3d")
        X, Y = np.meshgrid(lam, A)
        Z = pop.pivot(index="A", columns="lam", values=m).loc[A, lam].values
        ax.plot_surface(X, Y, Z, cmap="viridis_r", linewidth=0, antialiased=True, alpha=0.95)
        ax.scatter([opt[m]["lam"]], [opt[m]["A"]], [opt[m]["value"]], color="red", s=60, label="óptimo")
        ax.set_xlabel("λ [m]")
        ax.set_ylabel("A [m]")
        ax.set_zlabel(LABEL[m])
        ax.set_title(NAME[m])
        ax.legend()
        ax.view_init(28, -125)
    _save(fig, out, "poblacional_superficie_3d")


def cuts(pop, opt, out: Path, baseline=None):
    _style()
    fig, axs = plt.subplots(2, 2, figsize=(11, 7.4))
    for r, m in enumerate(("E1", "E2")):
        o = opt[m]
        a = pop[np.isclose(pop.lam, o["lam"])].sort_values("A")
        ax = axs[r, 0]
        ax.errorbar(a.A, a[m], yerr=a[m + "_sem"], marker="o", ms=4, capsize=3, color=C[m], label=f"λ = {o['lam']:g} m fijo")
        ax.axvline(o["A"], color="k", ls=":", lw=1)
        if baseline is not None:
            ax.axhline(baseline[m], color="gray", ls="--", lw=1, label="sin elusión")
        ax.set_xlabel(P1L)
        ax.set_ylabel(LABEL[m])
        ax.set_title(f"{NAME[m]}: corte en A")
        ax.legend(loc="best")
        b = pop[np.isclose(pop.A, o["A"])].sort_values("lam")
        ax = axs[r, 1]
        ax.errorbar(b.lam, b[m], yerr=b[m + "_sem"], marker="o", ms=4, capsize=3, color=C[m], label=f"A = {o['A']:g} m fijo")
        ax.axvline(o["lam"], color="k", ls=":", lw=1)
        if baseline is not None:
            ax.axhline(baseline[m], color="gray", ls="--", lw=1, label="sin elusión")
        ax.set_xlabel(P2L)
        ax.set_ylabel(LABEL[m])
        ax.set_title(f"{NAME[m]}: corte en λ")
        ax.legend(loc="best")
    fig.suptitle("Cortes 1D por el óptimo; barras = error estándar entre eventos (σ/√M)")
    fig.tight_layout()
    _save(fig, out, "poblacional_cortes")


def individual_params(ind, opt, out: Path):
    """Distribucion de los parametros optimos individuales (A y lam) por metrica, con el valor poblacional marcado."""
    _style()
    fig, axs = plt.subplots(2, 2, figsize=(10, 7))
    # columna de A: misma escala (y mismas clases) en ambas metricas para poder compararlas
    a_lo, a_hi = ind.A_opt.min(), ind.A_opt.max()
    a_w = (a_hi - a_lo) / 10                              # ancho de clase comun; clases centradas en los valores
    a_bins = a_lo - a_w / 2 + a_w * np.arange(11 + 1)
    for r, m in enumerate(("E1", "E2")):
        d = ind[ind.metric == m]
        for c, (col, lab, key) in enumerate((("A_opt", P1L, "A"), ("lam_opt", P2L, "lam"))):
            ax = axs[r, c]
            ax.hist(d[col], bins=a_bins if c == 0 else 12, color=C[m], alpha=0.75, edgecolor="white")
            if c == 0:
                ax.set_xlim(a_bins[0] - a_w, a_bins[-1] + a_w)
            mu, sd = d[col].mean(), d[col].std()          # desvio estandar muestral (ddof = 1) sobre los 19 eventos
            ax.axvspan(mu - sd, mu + sd, color=C[m], alpha=0.18, lw=0)
            ax.axvline(mu, color=C[m], lw=2, ls="--", label="promedio individual")
            ax.axvline(opt[m][key], color="k", lw=2, label="valor poblacional")
            ax.set_xlabel(lab)
            ax.set_ylabel("eventos")
            ax.set_title(f"{NAME[m]}: {key if key == 'A' else 'λ'} óptimo individual", fontsize=10)
            ax.margins(y=0.25)
            ax.yaxis.set_major_locator(plt.MaxNLocator(integer=True))     # cuentas de eventos: solo enteros
            ax.legend(loc="upper left" if c == 0 else "upper right", fontsize=8.5)
    fig.tight_layout()
    _save(fig, out, "individual_parametros")


def individual_errors(ind, out: Path):
    """Histograma del cociente E individual / E poblacional por evento, para cada metrica."""
    _style()
    fig, axs = plt.subplots(1, 2, figsize=(10, 4.4))
    for ax, m in zip(axs, ("E1", "E2")):
        d = ind[ind.metric == m]
        # cociente por evento (<= 1: el individual nunca es peor; 1 = sin mejora)
        ratio = d.E_individual / d.E_population
        mu, sd = ratio.mean(), ratio.std()
        w = 0.01 if ratio.min() > 0.8 else 0.05            # ancho de clase segun el rango de los cocientes
        lo = np.floor(ratio.min() / w) * w
        ax.hist(ratio, bins=np.arange(lo, 1.0 + 1e-9, w), color=C[m], alpha=0.75, edgecolor="white")
        ax.axvspan(mu - sd, mu + sd, color=C[m], alpha=0.18, lw=0)
        ax.axvline(mu, color=C[m], lw=2, ls="--", label="promedio")
        ax.axvline(1.0, color="k", lw=1.5, label="valor poblacional")
        ax.set_xlim(lo - 2 * w, 1.0 + 0.6 * max(w, 0.02))
        ax.set_xlabel("$E_m$ individual / $E_m$ poblacional (menor que 1 = mejora)")
        ax.set_ylabel("eventos")
        ax.set_title(NAME[m], fontsize=11)
        ax.margins(y=0.3)
        ax.legend(loc="upper left", fontsize=8.5)
    fig.tight_layout()
    _save(fig, out, "individual_errores")


def cv_plot(cv, cvs, out: Path):
    _style()
    fig, axs = plt.subplots(1, 2, figsize=(11, 4.4))
    for ax, m in zip(axs, ("E1", "E2")):
        d = cv[cv.metric == m]
        bp = ax.boxplot([d.E_train, d.E_validation], patch_artist=True, showfliers=False)
        ax.set_xticks([1, 2], ["entrenamiento\n(calibrado ahí)", "validación\n(calibrado en entren.)"])
        for p in bp["boxes"]:
            p.set_facecolor(C[m])
            p.set_alpha(0.5)
        v = cvs[cvs.metric == m].E_pop_all_events.iloc[0]
        ax.axhline(v, color="k", ls="--", lw=1, label=f"⟨E⟩ poblacional (b) = {v:.3f}")
        ax.set_ylabel("error medio [m²]")
        ax.set_title(f"{NAME[m]} ({len(d)} particiones)")
        ax.legend(loc="upper left")
    fig.tight_layout()
    fig.text(0.5, -0.01, "caja: cuartiles 25-75 %, línea naranja: mediana, bigotes: hasta 1,5 × IQR (no se muestran valores atípicos)",
             ha="center", va="top", fontsize=8, color="gray")
    _save(fig, out, "validacion_cruzada")


def event_overlay(event, run, out: Path, name: str):
    """Trayectorias experimentales vs simuladas + observable primario en el tiempo."""
    _style()
    fig = plt.figure(figsize=(13, 8))
    gs = fig.add_gridspec(2, 2, height_ratios=[1, 1.1])
    axs = [fig.add_subplot(gs[0, :]), fig.add_subplot(gs[1, 0]), fig.add_subplot(gs[1, 1])]
    ax = axs[0]
    for k, (aid, ex) in enumerate(event.agents.items()):
        sm = run.agents[aid]
        ax.plot(ex.x, ex.y, "o", ms=2.5, color=C["exp"], alpha=0.6, label="experimental" if k == 0 else None)
        ax.plot(sm.x, sm.y, "-", lw=1.8, color=C["sim"] if k == 0 else C["E2"], label=f"simulado (peatón {aid})")
        ax.plot(ex.x[0], ex.y[0], "s", color="k")
        ax.plot(ex.x[-1], ex.y[-1], "*", color="k", ms=10)
    ax.set_aspect("equal", adjustable="datalim")
    ax.set_xlabel("x [m]")
    ax.set_ylabel("y [m]")
    ax.set_title(f"Evento {event.event_id}: A = {run.A:g} m, λ = {run.lam:g} m (■ inicio, ★ target final); "
                 "el simulado se muestra hasta que llega al target")
    ax.legend(fontsize=8)
    ax = axs[1]
    for k, (aid, ex) in enumerate(event.agents.items()):
        sx, sy = sim_at_experimental_frames(ex, run.agents[aid])
        ax.plot(ex.t, (ex.x - sx) ** 2 + (ex.y - sy) ** 2, color=C["sim"] if k == 0 else C["E2"], label=f"peatón {aid}")
    ax.set_xlabel("t [s]")
    ax.set_ylabel(r"$|r_{exp}(t_k)-r_{sim}(t_k)|^2$ [m$^2$]")
    ax.set_title("Observable primario: error instantáneo (integrando de la Métrica 1)")
    ax.legend()
    ax = axs[2]
    a, b = [event.agents[i] for i in event.agents]
    sa, sb = run.agents[a.agent_id], run.agents[b.agent_id]
    common = np.intersect1d(a.frame, b.frame)
    ia = np.searchsorted(a.frame, common)
    ib = np.searchsorted(b.frame, common)
    ax.plot(a.t[ia], np.hypot(a.x[ia] - b.x[ib], a.y[ia] - b.y[ib]), "o", ms=2.5, color=C["exp"], label="experimental")
    ax.plot(sa.t, np.hypot(sa.x - sb.x, sa.y - sb.y), color=C["sim"], label="simulado")
    ax.axhline(0.5, color="gray", ls="--", lw=1, label="contacto (2r = 0.5 m)")
    ax.axvline(a.t[-1], color="k", ls=":", lw=1, label="fin del evento experimental")
    ax.set_xlabel("t [s]")
    ax.set_ylabel("distancia entre peatones [m]")
    ax.set_title("Distancia relativa")
    ax.legend()
    fig.tight_layout()
    _save(fig, out, name)


def region_trajectories(events, runs, pairs, opt, m, out: Path, name: str):
    """Para varios eventos: trayectoria experimental y simuladas de TODOS los pares (A, lam) del conjunto de indiferencia
    de la metrica m (dentro de 1 error estandar del optimo). El optimo se resalta. runs: {(evento, A, lam): Run}."""
    _style()
    n = len(events)
    cols = 2
    rows = int(np.ceil(n / cols))
    fig, axs = plt.subplots(rows, cols, figsize=(6.6 * cols, 3.2 * rows), squeeze=False)
    o = (round(opt[m]["A"], 6), round(opt[m]["lam"], 6))
    for ax, ev in zip(axs.ravel(), events):
        for k, (aid, ex) in enumerate(ev.agents.items()):
            for pr in pairs.itertuples():
                run = runs[(ev.event_id, float(pr.A), float(pr.lam))]
                sm = run.agents[aid]
                ax.plot(sm.x, sm.y, "-", lw=0.8, color=C[m], alpha=0.25,
                        label=f"simulado, {len(pairs)} pares de la región óptima" if (k == 0 and pr.Index == pairs.index[0]) else None)
            so = runs[(ev.event_id, *o)].agents[aid]
            ax.plot(so.x, so.y, "-", lw=2.0, color="k", label=f"simulado con el óptimo (A = {o[0]:g}, λ = {o[1]:g})" if k == 0 else None)
            ax.plot(ex.x, ex.y, "o", ms=2.5, color=C["exp"], alpha=0.6, label="experimental" if k == 0 else None)
            ax.plot(ex.x[0], ex.y[0], "s", color="k")
            ax.plot(ex.x[-1], ex.y[-1], "*", color="k", ms=10)
        ax.set_title(f"Evento {ev.event_id}")
        ax.set_aspect("equal", adjustable="datalim")
        ax.set_xlabel("x [m]")
        ax.set_ylabel("y [m]")
    for ax in axs.ravel()[n:]:
        ax.axis("off")
    h, l = axs.ravel()[0].get_legend_handles_labels()
    fig.legend(h, l, loc="lower center", ncol=3, fontsize=9, bbox_to_anchor=(0.5, -0.02))
    fig.suptitle(f"{NAME[m]}: trayectorias de los pares dentro de 1 error estándar del óptimo (■ inicio, ★ target)")
    fig.tight_layout(rect=(0, 0.04, 1, 1))
    _save(fig, out, name)