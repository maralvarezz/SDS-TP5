#!/usr/bin/env python3
"""Pipeline del Problema 1. Uso (desde la raiz del repo):

    python viz/run_analysis.py all                      # eventos -> grilla -> poblacional -> individual -> CV -> graficos
    python viz/run_analysis.py all --offline            # NUNCA invoca Java: falla si faltan corridas
    python viz/run_analysis.py plots|animations --offline

Etapas que no cambian la dinamica (graficos, animaciones, CV, agregaciones) leen los TXT existentes.
Java solo se invoca para combinaciones (evento, A, lam) sin TXT completo."""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sdstp5 import animations, experiments as ex, plots  # noqa: E402
from sdstp5.calibration import Engine, EngineError, ErrorStore, evaluate  # noqa: E402
from sdstp5.readers import read_events_dir, read_run  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["all", "events", "grid", "analyze", "plots", "animations"])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--config", default="input/analysis_config.json")
    ap.add_argument("--offline", action="store_true", help="no invocar el motor Java")
    ap.add_argument("--exclude", type=int, nargs="*", default=None, help="eventos a excluir (sobrescribe la config)")
    ap.add_argument("--tag", default="analysis", help="subcarpeta de output/ para los derivados")
    ap.add_argument("--threads", type=int, default=None)
    ap.add_argument("--animation-events", nargs="+", default=None, metavar="N|all",
                    help="eventos a animar / superponer (por defecto los de analysis_config.json); 'all' = todos")
    a = ap.parse_args()

    root = Path(a.root).resolve()
    cfg = json.loads((root / a.config).read_text())
    excl = set(a.exclude if a.exclude is not None else cfg.get("excludeEvents", []))
    out = root / "output" / a.tag
    out.mkdir(parents=True, exist_ok=True)
    timing = {}

    engine = Engine(root, cfg["baseConfig"], threads=a.threads, offline=a.offline)  # offline: nunca llama a Java
    # animaciones: misma dinamica pero la corrida sigue hasta que ambos llegan al target + 2 s (NO se usa para las metricas)
    anim_engine = Engine(root, cfg.get("animationConfig", cfg["baseConfig"]), threads=a.threads, offline=a.offline)

    if a.stage in ("all", "events"):
        if a.offline:
            print("[offline] se usan los eventos exportados existentes")
        else:
            engine.export_events()
    events = read_events_dir(root / cfg["eventsDir"])
    ev_ids = [e for e in sorted(events) if e not in excl]
    print(f"Eventos: {len(ev_ids)} (excluidos: {sorted(excl)})")
    store = ErrorStore(root, root / cfg["eventsDir"])
    if a.animation_events is None:
        anim_events = list(cfg["animations"]["events"])
    elif a.animation_events == ["all"]:
        anim_events = list(ev_ids)
    else:
        anim_events = [int(v) for v in a.animation_events]

    table_path = out / "grid_errors.csv"
    if a.stage in ("all", "grid", "analyze", "plots"):
        t0 = time.perf_counter()
        points = ex.make_grid(cfg)
        table = evaluate(engine, store, points, ev_ids)
        table.to_csv(table_path, index=False)
        bl = cfg["baseline_no_avoidance"]
        # referencia "sin elusion": otro archivo de configuracion (avoidance.enabled = false), mismo SFM y mismos eventos
        base_engine = Engine(root, cfg["baselineConfig"], threads=a.threads, offline=a.offline)
        base = evaluate(base_engine, store, [(bl["amplitude_m"], bl["lambda_m"])], ev_ids)
        base.to_csv(out / "baseline_no_avoidance.csv", index=False)
        timing["physical_simulation_s_this_invocation"] = engine.total_physical_seconds
        timing["errors_and_grid_s_total"] = time.perf_counter() - t0
        print(f"Grilla: {len(points)} combinaciones x {len(ev_ids)} eventos")
        if a.stage == "grid":
            _save_timing(out, timing, store)
            return

    table = pd.read_csv(table_path)
    base = pd.read_csv(out / "baseline_no_avoidance.csv")
    pop = ex.population_table(table)
    opt = ex.population_optima(pop)
    baseline = {m: float(base[m].mean()) for m in ex.METRICS}

    if a.stage in ("all", "analyze", "plots"):
        pop.to_csv(out / "population_surface.csv", index=False)
        ex.save_json(out / "population_optima.json", {"optima": opt, "baseline_no_avoidance": baseline,
                                                      "events": ev_ids, "excluded": sorted(excl)})
        for m in ex.METRICS:
            ex.indifference_set(pop, m).to_csv(out / f"indifference_set_{m}.csv", index=False)
        # item c: lam se fija en el optimo poblacional (item b) y se calibra A por evento; la version literal (A y lam por evento)
        # se guarda aparte como referencia
        fix = bool(cfg.get("individual", {}).get("fixLambdaToPopulation", False))
        ex.individual_calibration(table, opt, fix_lambda=False).to_csv(out / "individual_calibration_2d.csv", index=False)
        ind = ex.individual_calibration(table, opt, fix_lambda=fix)
        ind.to_csv(out / "individual_calibration.csv", index=False)
        ex.individual_summary(ind).to_csv(out / "individual_summary.csv", index=False)
        cvc = cfg["crossValidation"]
        cv, parts = ex.cross_validation(table, opt, cvc["seed"], cvc["repeats"], cvc["trainFraction"])
        cv.to_csv(out / "cross_validation.csv", index=False)
        ex.save_json(out / "cross_validation_partitions.json", {"seed": cvc["seed"], "partitions": parts})
        cvs = ex.cv_summary(cv, opt)
        cvs.to_csv(out / "cross_validation_summary.csv", index=False)
        print(json.dumps(opt, indent=1))
        print("baseline sin elusion:", baseline)
        print(ex.individual_summary(ind).round(4).to_string())
        print(cvs.round(4).to_string())

    if a.stage in ("all", "plots"):
        t0 = time.perf_counter()
        ind = pd.read_csv(out / "individual_calibration.csv")
        cv = pd.read_csv(out / "cross_validation.csv")
        cvs = pd.read_csv(out / "cross_validation_summary.csv")
        figs = out / "figures"
        lam_max = cfg.get("plots", {}).get("heatmapLambdaMax")
        region = {m: pd.read_csv(out / f"indifference_set_{m}.csv") for m in ex.METRICS}
        plots.surface_heatmaps(pop, opt, figs, lam_max=lam_max)
        plots.cuts(pop, opt, figs, baseline)
        plots.individual_params(ind, opt, figs)
        plots.individual_errors(ind, figs)
        plots.cv_plot(cv, cvs, figs)
        for e in anim_events:
            if e in ev_ids:
                _overlay(anim_engine, store, events, e, opt["E1"], figs, f"evento{e:02d}_E1opt")
        # trayectorias de los pares de la region optima (corridas extendidas, como las superposiciones por evento)
        reg_events = [e for e in cfg.get("plots", {}).get("regionEvents", cfg["animations"]["events"]) if e in ev_ids]
        for m in ex.METRICS:
            pairs = region[m]
            tasks = [(e, float(r.A), float(r.lam)) for e in reg_events for r in pairs.itertuples()]
            plan = anim_engine.request(tasks, log=lambda *_: None)   # corridas extendidas: llegan al target
            runs = {(int(r.event_id), float(r.A), float(r.lam)): read_run(store.root / r.path) for r in plan.itertuples()}
            plots.region_trajectories([events[e] for e in reg_events], runs, pairs, opt, m, figs, f"region_optima_trayectorias_{m}")
        timing["plots_s"] = time.perf_counter() - t0

    if a.stage in ("all", "animations"):
        t0 = time.perf_counter()
        for e in anim_events:
            if e in ev_ids:
                run = _run_for(anim_engine, store, e, opt["E1"])
                f = animations.animate_event(events[e], run, out / "animations" / f"evento{e:02d}_E1opt",
                                             fmt=cfg["animations"].get("format", "mp4"))
                print("animacion:", f)
        timing["animations_s"] = time.perf_counter() - t0
    _save_timing(out, timing, store)


def _run_for(engine, store, e, params):
    plan = engine.request([(e, params["A"], params["lam"])], log=lambda *_: None)
    return read_run(store.root / plan.iloc[0].path)


def _overlay(engine, store, events, e, params, figs, name):
    # corrida extendida (config de animacion): el simulado sigue hasta llegar al target; el error solo se evalua en los
    # instantes experimentales, asi que el panel de error no cambia
    plots.event_overlay(events[e], _run_for(engine, store, e, params), figs, name)


def _save_timing(out, timing, store):
    timing["postprocessing_error_computation_s"] = store.postprocess_seconds
    (out / "timing.json").write_text(json.dumps(timing, indent=2))


if __name__ == "__main__":
    main()
