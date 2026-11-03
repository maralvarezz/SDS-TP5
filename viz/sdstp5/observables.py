"""Metricas de error (consigna, ec. 1-3). Son ERRORES CUADRATICOS MEDIOS en m^2 (no RMSE).

E1_i = (1/N) sum_k |r_exp(t_k) - r_sim(t_k)|^2                        (a iguales tiempos)
E2_i = (1/N) sum_k min_j |r_exp(t_k) - r_sim(t_j)|^2                  (punto mas cercano de la trayectoria simulada)
E_m  = promedio de los 2 peatones;  <E> = promedio sobre eventos.
"""
from __future__ import annotations

import numpy as np

from .readers import AgentTrack, EventData, Run


def sim_at_experimental_frames(exp: AgentTrack, sim: AgentTrack, tol: float = 1e-3):
    """Posicion simulada en los frames experimentales (el motor guarda exactamente en cada t_k; sin interpolar)."""
    idx = np.searchsorted(sim.frame, exp.frame)
    idx = np.clip(idx, 0, len(sim.frame) - 1)
    # cada frame experimental debe coincidir con una salida simulada
    near = np.abs(sim.frame[idx] - exp.frame) <= tol
    if not near.all():
        left = np.clip(idx - 1, 0, len(sim.frame) - 1)
        better = np.abs(sim.frame[left] - exp.frame) <= tol
        idx = np.where(near, idx, left)
        near = near | better
    if not near.all():
        raise ValueError("La simulacion no tiene estados en todos los instantes experimentales (Metrica 1 no calculable)")
    return sim.x[idx], sim.y[idx]


def metric1(exp_t, exp_x, exp_y, sim_t, sim_x, sim_y, tol: float = 1e-6) -> float:
    """Metrica 1 sobre arreglos: sim debe tener muestras en cada exp_t (dentro de tol)."""
    exp_t, sim_t = np.asarray(exp_t, float), np.asarray(sim_t, float)
    idx = np.searchsorted(sim_t, exp_t)
    idx = np.clip(idx, 1, len(sim_t) - 1)
    pick = np.where(np.abs(sim_t[idx - 1] - exp_t) <= np.abs(sim_t[idx] - exp_t), idx - 1, idx)
    if np.max(np.abs(sim_t[pick] - exp_t)) > tol:
        raise ValueError("La simulacion no tiene estados en todos los instantes experimentales")
    dx = np.asarray(exp_x, float) - np.asarray(sim_x, float)[pick]
    dy = np.asarray(exp_y, float) - np.asarray(sim_y, float)[pick]
    return float(np.mean(dx * dx + dy * dy))


def metric2(exp_x, exp_y, sim_x, sim_y) -> float:
    """Metrica 2: para cada punto experimental, minimo (sobre TODOS los puntos simulados) de la distancia^2."""
    ex = np.asarray(exp_x, float)[:, None]
    ey = np.asarray(exp_y, float)[:, None]
    d2 = (ex - np.asarray(sim_x, float)[None, :]) ** 2 + (ey - np.asarray(sim_y, float)[None, :]) ** 2
    return float(np.mean(d2.min(axis=1)))


def agent_errors(exp: AgentTrack, sim: AgentTrack) -> tuple[float, float]:
    sx, sy = sim_at_experimental_frames(exp, sim)
    e1 = float(np.mean((exp.x - sx) ** 2 + (exp.y - sy) ** 2))
    e2 = metric2(exp.x, exp.y, sim.x, sim.y)
    return e1, e2


def event_errors(event: EventData, run: Run) -> dict:
    """E1 y E2 del evento (promedio de 2 peatones) y por peaton."""
    per = {}
    for aid, exp in event.agents.items():
        if aid not in run.agents:
            raise ValueError(f"El peaton {aid} del evento {event.event_id} no esta en {run.path}")
        per[aid] = agent_errors(exp, run.agents[aid])
    e1 = float(np.mean([v[0] for v in per.values()]))
    e2 = float(np.mean([v[1] for v in per.values()]))
    return {"E1": e1, "E2": e2, "per_agent": per}


def population_error(event_errors_list) -> float:
    """Error poblacional <E> = promedio simple sobre eventos."""
    return float(np.mean(event_errors_list))
