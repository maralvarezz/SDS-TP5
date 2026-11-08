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


def surface_heatmaps(pop, opt, out: Path, baseline=None):
    _style()
    A = np.sort(pop.A.unique())
    lam = np.sort(pop.lam.unique())
    fig, axs = plt.subplots(1, 2, figsize=(12, 4.6))
    for ax, m in zip(axs, ("E1", "E2")):
        Z = pop.pivot(index="A", columns="lam", values=m).loc[A, lam].values
        im = ax.pcolormesh(np.arange(len(lam)), np.arange(len(A)), Z, shading="nearest", cmap="viridis_r")
        ax.set_yticks(np.arange(len(A)))
        ax.set_yticklabels([f"{v:g}" for v in A])
        ax.set_xticks(np.arange(len(lam)))
        ax.set_xticklabels([f"{v:g}" for v in lam], rotation=60)
        ax.grid(False)
        i = int(np.where(np.isclose(A, opt[m]["A"]))[0][0])
        j = int(np.where(np.isclose(lam, opt[m]["lam"]))[0][0])
        ax.plot(j, i, marker="*", ms=16, color="white", mec="black", label=f"óptimo (A = {opt[m]['A']:g} m, λ = {opt[m]['lam']:g} m)")
        ax.set_xlabel(P2L)
        ax.set_ylabel(P1L)
        ax.set_title(NAME[m])
        ax.legend(loc="upper right")
        cb = fig.colorbar(im, ax=ax)
        cb.set_label(LABEL[m])
    fig.suptitle("Calibración poblacional: error en la grilla (A, λ); ejes en categorías (no a escala)")
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
            ax.axhline(baseline[m], color="gray", ls="--", lw=1, label="sin elusión (referencia)")
        # A en escala logaritmica (la grilla es geometrica); lam queda lineal
        ax.set_xscale("log")
        ticks = [t for t in (0.05, 0.1, 0.2, 0.5, 1, 2, 5) if a.A.min() * 0.99 <= t <= a.A.max() * 1.01]
        ax.set_xticks(ticks)
        ax.set_xticklabels([f"{t:g}" for t in ticks])
        ax.xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
        ax.set_xlabel(P1L + " (escala log)")
        ax.set_ylabel(LABEL[m])
        ax.set_title(f"{NAME[m]}: corte en A")
        ax.legend(loc="best")
        b = pop[np.isclose(pop.A, o["A"])].sort_values("lam")
        ax = axs[r, 1]
        ax.errorbar(b.lam, b[m], yerr=b[m + "_sem"], marker="o", ms=4, capsize=3, color=C[m], label=f"A = {o['A']:g} m fijo")
        ax.axvline(o["lam"], color="k", ls=":", lw=1)
        if baseline is not None:
            ax.axhline(baseline[m], color="gray", ls="--", lw=1, label="sin elusión (referencia)")
        ax.set_xlabel(P2L)
        ax.set_ylabel(LABEL[m])
        ax.set_title(f"{NAME[m]}: corte en λ")
        ax.legend(loc="best")
    fig.suptitle("Cortes 1D por el óptimo; barras = error estándar entre eventos (σ/√M)")
    fig.tight_layout()
    _save(fig, out, "poblacional_cortes")


def individual_params(ind, opt, out: Path):
    _style()
    fig, axs = plt.subplots(2, 3, figsize=(13, 7))
    for r, m in enumerate(("E1", "E2")):
        d = ind[ind.metric == m]
        for c, (col, lab) in enumerate((("A_opt", P1L), ("lam_opt", P2L))):
            ax = axs[r, c]
            ax.hist(d[col], bins=12, color=C[m], alpha=0.75, edgecolor="white")
            ax.axvline(opt[m]["A" if c == 0 else "lam"], color="k", lw=2, label="valor poblacional")
            ax.set_xlabel(lab)
            ax.set_ylabel("eventos")
            ax.set_title(f"{NAME[m]}: {col}")
            ax.legend()
        ax = axs[r, 2]
        ax.scatter(d.lam_opt, d.A_opt, color=C[m], s=36)
        ax.scatter([opt[m]["lam"]], [opt[m]["A"]], marker="*", s=220, color="k", label="poblacional")
        ax.set_xlabel(P2L)
        ax.set_ylabel(P1L)
        ax.set_title("óptimos individuales")
        ax.legend()
    fig.tight_layout()
    _save(fig, out, "individual_parametros")


def individual_errors(ind, out: Path):
    _style()
    fig, axs = plt.subplots(1, 4, figsize=(15, 4.4))
    for k, m in enumerate(("E1", "E2")):
        d = ind[ind.metric == m]
        ax = axs[2 * k]
        bp = ax.boxplot([d.E_population, d.E_individual], patch_artist=True)
        ax.set_xticks([1, 2], ["parámetros\npoblacionales", "parámetros\nindividuales"])
        for p in bp["boxes"]:
            p.set_facecolor(C[m])
            p.set_alpha(0.5)
        ax.set_ylabel(LABEL[m].replace(r"\langle", "").replace(r"\rangle", ""))
        ax.set_title(f"{NAME[m]}: distribución de $E_m$")
        ax = axs[2 * k + 1]
        lim = max(d.E_population.max(), d.E_individual.max()) * 1.05
        ax.plot([0, lim], [0, lim], color="gray", lw=1)
        ax.scatter(d.E_population, d.E_individual, color=C[m], s=36)
        ax.set_xlabel("$E_m$ con parámetros poblacionales [m²]")
        ax.set_ylabel("$E_m$ con parámetros individuales [m²]")
        ax.set_title("por evento (debajo de la diagonal = mejora)")
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
    ax.set_title(f"Evento {event.event_id}: A = {run.A:g} m, λ = {run.lam:g} m (■ inicio, ★ target final)")
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
    ax.set_xlabel("t [s]")
    ax.set_ylabel("distancia entre peatones [m]")
    ax.set_title("Distancia relativa")
    ax.legend()
    fig.tight_layout()
    _save(fig, out, name)
