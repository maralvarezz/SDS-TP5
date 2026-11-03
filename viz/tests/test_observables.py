import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sdstp5 import experiments as ex  # noqa: E402
from sdstp5.observables import (agent_errors, event_errors, metric1, metric2,  # noqa: E402
                                population_error, sim_at_experimental_frames)
from sdstp5.readers import AgentTrack, EventData, Run  # noqa: E402


def track(aid, t, x, y, fps=10.0, f0=0):
    t = np.asarray(t, float)
    return AgentTrack(agent_id=aid, t=t, frame=f0 + np.rint(t * fps), x=np.asarray(x, float), y=np.asarray(y, float),
                      vx=np.zeros_like(t), vy=np.zeros_like(t), theta=np.zeros_like(t))


def test_identical_trajectories_give_zero():
    t = np.linspace(0, 5, 51)
    x, y = t * 1.2, np.sin(t)
    assert metric1(t, x, y, t, x, y) == 0.0
    assert metric2(x, y, x, y) == 0.0


def test_metric1_time_shift_and_squared_units():
    # recta a v = 1 m/s; la simulada va 0.3 s atrasada => error de posicion 0.3 m => E1 = 0.09 m^2 (NO 0.3, no RMSE)
    t = np.linspace(0, 5, 101)
    exp_x, exp_y = t.copy(), np.zeros_like(t)
    sim_t = np.linspace(0, 5, 501)
    sim_x = np.clip(sim_t - 0.3, 0, None)
    e1 = metric1(t[10:], exp_x[10:], exp_y[10:], sim_t, sim_x, np.zeros_like(sim_t))
    assert e1 == pytest.approx(0.09, abs=1e-12)


def test_metric2_ignores_time_shift_but_sees_geometry():
    t = np.linspace(0, 5, 501)
    exp_x, exp_y = t[100:400], np.zeros(300)
    sim_x, sim_y = np.clip(t - 0.3, 0, None), np.zeros_like(t)
    assert metric2(exp_x, exp_y, sim_x, sim_y) == pytest.approx(0.0, abs=1e-12)   # misma forma, distinto tiempo
    assert metric2(exp_x, exp_y, sim_x, sim_y + 0.2) == pytest.approx(0.04, abs=1e-12)  # desvio geometrico 0.2 m


def test_metric2_takes_minimum_over_all_simulated_points():
    ex_, ey_ = [0.0], [0.0]
    sx, sy = [3.0, 1.0, -0.5, 2.0], [0.0, 0.0, 0.0, 0.0]
    assert metric2(ex_, ey_, sx, sy) == pytest.approx(0.25)


def test_metric1_requires_states_at_every_experimental_time():
    with pytest.raises(ValueError):
        metric1([0.0, 0.1], [0, 0], [0, 0], [0.0, 1.0], [0, 0], [0, 0])


def test_event_and_population_aggregation():
    # dos peatones: errores constantes 0.04 y 0.16 en x => E1 evento = 0.10; dos eventos 0.10 y 0.30 => <E1> = 0.20
    t = np.linspace(0, 1, 11)

    def mk(eid, off_a, off_b):
        ev = EventData(path=Path("x"), header={}, event_id=eid, fps=10.0, start_frame=0, end_frame=10,
                       agents={"a": track("a", t, t, 0 * t), "b": track("b", t, t, 0 * t + 5)})
        run = Run(path=Path("r"), header={}, event_id=eid, p1=1, p2=1,
                  agents={"a": track("a", t, t + off_a, 0 * t), "b": track("b", t, t + off_b, 0 * t + 5)})
        return event_errors(ev, run)

    e_a = mk(1, 0.2, 0.4)       # 0.04 y 0.16
    assert e_a["E1"] == pytest.approx(0.10)
    e_b = mk(2, 0.4, 0.8)       # 0.16 y 0.64
    assert e_b["E1"] == pytest.approx(0.40)
    assert population_error([e_a["E1"], e_b["E1"]]) == pytest.approx(0.25)


def test_sim_sampled_finer_than_experiment_matches_by_frame():
    exp = track("a", np.arange(0, 1.01, 0.1), np.arange(0, 1.01, 0.1), np.zeros(11))
    t = np.arange(0, 1.0001, 1 / 30)
    sim = AgentTrack("a", t, np.rint(t * 10 * 1e4) / 1e4, t.copy(), np.zeros_like(t))
    sx, _ = sim_at_experimental_frames(exp, sim)
    assert np.allclose(sx, exp.x)
    assert agent_errors(exp, sim)[0] == pytest.approx(0.0, abs=1e-12)


def _table():
    rng = np.random.default_rng(0)
    rows = []
    for e in range(1, 11):
        for p1 in (1.0, 2.0):
            for p2 in (0.5, 0.7, 0.9):
                base = (p2 - (0.5 + 0.02 * e)) ** 2 + 0.01 * p1
                rows.append(dict(event_id=e, p1=p1, p2=p2, E1=base, E2=base / 2))
    return pd.DataFrame(rows)


def test_population_and_individual_optimum():
    tab = _table()
    pop = ex.population_table(tab)
    opt = ex.population_optima(pop)
    assert opt["E1"]["p1"] == 1.0 and opt["E1"]["p2"] == 0.7
    ind = ex.individual_calibration(tab, opt)
    assert (ind.E_individual <= ind.E_population + 1e-15).all()       # individualizar nunca empeora (misma grilla)
    assert set(ind.metric) == {"E1", "E2"}


def test_cross_validation_is_reproducible_and_has_no_leakage():
    tab = _table()
    opt = ex.population_optima(ex.population_table(tab))
    cv1, parts1 = ex.cross_validation(tab, opt, seed=1, repeats=20, train_fraction=0.7)
    cv2, parts2 = ex.cross_validation(tab, opt, seed=1, repeats=20, train_fraction=0.7)
    assert parts1 == parts2 and cv1.equals(cv2)
    for p in parts1:
        assert set(p["train"]).isdisjoint(p["validation"])
        assert sorted(p["train"] + p["validation"]) == list(range(1, 11))
    # la eleccion depende solo de entrenamiento: alterar eventos de validacion no cambia (p1, p2) elegidos
    rep0 = parts1[0]
    tab2 = tab.copy()
    tab2.loc[tab2.event_id.isin(rep0["validation"]), ["E1", "E2"]] *= 1000
    cv3, _ = ex.cross_validation(tab2, opt, seed=1, repeats=1, train_fraction=0.7)
    a = cv1[cv1.repeat == 0].set_index("metric")[["p1_train", "p2_train"]]
    b = cv3[cv3.repeat == 0].set_index("metric")[["p1_train", "p2_train"]]
    assert a.equals(b)
