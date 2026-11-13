"""Experimentos del Problema 1 (b, c, d) calculados sobre errores de corridas persistidas.

Todo se hace sobre una unica grilla (A, lam): la calibracion poblacional, la individual y la validacion cruzada
buscan el optimo sobre las MISMAS combinaciones, de modo que las comparaciones sean justas (misma resolucion).
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

METRICS = ("E1", "E2")


def make_grid(cfg: dict) -> list[tuple[float, float]]:
    g = cfg["grid"]
    A = [float(v) for v in g["amplitude_m"]]
    lam = [float(v) for v in g["lambda_m"]]
    return [(a, b) for a in A for b in lam]


def population_table(table: pd.DataFrame) -> pd.DataFrame:
    """<E1>, <E2> por (A, lam) con desvio entre eventos y error estandar (std/sqrt(M))."""
    g = table.groupby(["A", "lam"])
    out = g.agg(M=("event_id", "nunique"), E1=("E1", "mean"), E1_std=("E1", "std"),
                E2=("E2", "mean"), E2_std=("E2", "std")).reset_index()
    out["E1_sem"] = out.E1_std / np.sqrt(out.M)
    out["E2_sem"] = out.E2_std / np.sqrt(out.M)
    return out


def argmin_row(df: pd.DataFrame, col: str) -> pd.Series:
    """Fila de minimo; los empates se resuelven de forma determinista (menor A, luego menor lam)."""
    m = df[col].min()
    cand = df[np.isclose(df[col], m, rtol=0, atol=1e-12)].sort_values(["A", "lam"])
    return cand.iloc[0]


def population_optima(pop: pd.DataFrame) -> dict:
    res = {}
    for m in METRICS:
        r = argmin_row(pop, m)
        res[m] = {"A": float(r.A), "lam": float(r.lam), "value": float(r[m]), "sem": float(r[m + "_sem"])}
    # cada optimo evaluado con la otra metrica (comparacion de optimos)
    for m, other in (("E1", "E2"), ("E2", "E1")):
        r = pop[(np.isclose(pop.A, res[m]["A"])) & (np.isclose(pop.lam, res[m]["lam"]))].iloc[0]
        res[m]["other_metric_value"] = float(r[other])
    return res


def indifference_set(pop: pd.DataFrame, metric: str) -> pd.DataFrame:
    """Combinaciones cuyo <E> esta dentro de 1 error estandar del optimo (region de indiferencia estadistica)."""
    best = argmin_row(pop, metric)
    return pop[pop[metric] <= best[metric] + best[metric + "_sem"]]


def individual_calibration(table: pd.DataFrame, pop_opt: dict, fix_lambda: bool = False) -> pd.DataFrame:
    """Para cada evento y metrica: optimo sobre la grilla, error individual y error con parametros poblacionales.
    fix_lambda=False: se optimiza el par (A_m, lam_m) (lectura literal de la consigna).
    fix_lambda=True: lam se fija en el optimo POBLACIONAL de esa metrica (ítem b) y solo se optimiza A_m por evento."""
    rows = []
    for ev, d in table.groupby("event_id"):
        for m in METRICS:
            dm = d[np.isclose(d.lam, pop_opt[m]["lam"])] if fix_lambda else d
            r = argmin_row(dm, m)
            pp = d[(np.isclose(d.A, pop_opt[m]["A"])) & (np.isclose(d.lam, pop_opt[m]["lam"]))].iloc[0]
            rows.append({"event_id": int(ev), "metric": m, "A_opt": float(r.A), "lam_opt": float(r.lam),
                         "E_individual": float(r[m]), "E_population": float(pp[m])})
    out = pd.DataFrame(rows)
    out["gain_abs"] = out.E_population - out.E_individual
    out["gain_rel"] = out.gain_abs / out.E_population
    return out


def individual_summary(ind: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for m, d in ind.groupby("metric"):
        rows.append({"metric": m, "mean_E_population": d.E_population.mean(), "mean_E_individual": d.E_individual.mean(),
                     "median_E_population": d.E_population.median(), "median_E_individual": d.E_individual.median(),
                     "mean_gain_abs": d.gain_abs.mean(), "mean_gain_rel_of_means": d.gain_abs.mean() / d.E_population.mean(),
                     "median_gain_rel": d.gain_rel.median(), "events_improved": int((d.gain_abs > 1e-12).sum()),
                     "events": int(len(d)), "A_opt_median": d.A_opt.median(), "lam_opt_median": d.lam_opt.median(),
                     "A_opt_iqr": float(d.A_opt.quantile(.75) - d.A_opt.quantile(.25)),
                     "lam_opt_iqr": float(d.lam_opt.quantile(.75) - d.lam_opt.quantile(.25))})
    return pd.DataFrame(rows)


def cross_validation(table: pd.DataFrame, pop_opt: dict, seed: int, repeats: int, train_fraction: float):
    """Particiones aleatorias entrenamiento/validacion de EVENTOS. Se calibra solo con entrenamiento
    (<E> sobre los eventos de entrenamiento, minimo en la grilla) y se evalua en validacion."""
    events = np.array(sorted(table.event_id.unique()))
    M = len(events)
    n_train = int(round(train_fraction * M))
    if not 1 <= n_train < M:
        raise ValueError("trainFraction deja un conjunto vacio")
    rng = np.random.default_rng(seed)
    piv = {m: table.pivot_table(index="event_id", columns=["A", "lam"], values=m) for m in METRICS}
    rows, parts = [], []
    for rep in range(repeats):
        perm = rng.permutation(events)
        train, val = np.sort(perm[:n_train]), np.sort(perm[n_train:])
        parts.append({"repeat": rep, "train": [int(x) for x in train], "validation": [int(x) for x in val]})
        for m in METRICS:
            tr_mean = piv[m].loc[train].mean(axis=0)            # solo entrenamiento
            best = tr_mean.idxmin()                             # (A, lam) elegido sin ver validacion
            e_val = float(piv[m].loc[val, best].mean())
            e_val_pop = float(piv[m].loc[val, (pop_opt[m]["A"], pop_opt[m]["lam"])].mean())  # parametros de (b), calibrados con TODOS los eventos
            e_tr = float(tr_mean.min())
            rows.append({"repeat": rep, "metric": m, "A_train": best[0], "lam_train": best[1], "E_train": e_tr,
                         "E_validation": e_val, "E_validation_with_population_params": e_val_pop})
    return pd.DataFrame(rows), parts


def cv_summary(cv: pd.DataFrame, pop_opt: dict) -> pd.DataFrame:
    rows = []
    for m, d in cv.groupby("metric"):
        diff = d.E_validation - d.E_validation_with_population_params
        rows.append({"metric": m, "repeats": len(d), "E_pop_all_events": pop_opt[m]["value"],
                     "E_train_mean": d.E_train.mean(), "E_validation_mean": d.E_validation.mean(),
                     "E_validation_std": d.E_validation.std(),
                     "E_val_with_pop_params_mean": d.E_validation_with_population_params.mean(),
                     "mean_diff_vs_pop_params": diff.mean(), "frac_repeats_worse_than_pop": float((diff > 1e-12).mean()),
                     "A_train_median": d.A_train.median(), "lam_train_median": d.lam_train.median(),
                     "A_train_iqr": float(d.A_train.quantile(.75) - d.A_train.quantile(.25)),
                     "lam_train_iqr": float(d.lam_train.quantile(.75) - d.lam_train.quantile(.25))})
    return pd.DataFrame(rows)


def save_json(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, default=float))
