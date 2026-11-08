"""Calibracion: genera combinaciones (evento, A, lam), detecta corridas faltantes, invoca al motor Java SOLO
para las faltantes y evalua las metricas sobre los TXT persistidos (con cache de errores derivados).

El motor Java es la unica fuente de verdad del identificador de corrida (hash); aca nunca se lo recalcula."""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

from .observables import event_errors
from .readers import read_events_dir, read_run

ERRORS_VERSION = "1"


class EngineError(RuntimeError):
    pass


def find_classpath(root: Path) -> str:
    env = os.environ.get("SDS_TP5_CLASSPATH")
    if env:
        return env
    for cand in (root / "simulation/target/sds-tp5.jar", root / "simulation/target/classes"):
        if cand.exists():
            return str(cand)
    raise EngineError("No se encontro el motor compilado. Ejecutar 'mvn package' en simulation/ "
                      "o definir SDS_TP5_CLASSPATH.")


class Engine:
    """Envoltorio del CLI Java (modo batch con cache)."""

    def __init__(self, root, base_config="input/base_config.json", threads: int | None = None, classpath=None,
                 offline: bool = False):
        self.root = Path(root).resolve()
        self.base_config = base_config
        self.threads = threads or max(1, (os.cpu_count() or 1))
        self.offline = offline
        # en modo offline NUNCA se invoca Java: ni siquiera se busca el motor compilado
        self.classpath = None if offline else (classpath or find_classpath(self.root))
        self.last_physical_seconds = 0.0
        self.total_physical_seconds = 0.0

    def _request_offline(self, tasks) -> pd.DataFrame:
        """Resuelve (evento, A, lam) -> TXT usando solo output/manifest.tsv y verificando el marcador END. Sin Java."""
        mpath = self.root / "output" / "manifest.tsv"
        if not mpath.exists():
            raise EngineError("modo offline: no existe output/manifest.tsv (no hay corridas persistidas)")
        m = pd.read_csv(mpath, sep="\t", dtype={"run_id": str})
        m = m[m.status == "ok"]
        if "config" in m.columns:   # separa corridas segun su archivo de configuracion base (evento completo vs animacion, etc.)
            m = m[m.config == Path(self.base_config).name]
        idx = {(int(r.event_id), float(r.amplitude_m), float(r.lambda_m)): r for r in m.itertuples()}
        rows, missing = [], []
        for e, a, b in tasks:
            r = idx.get((e, a, b))
            if r is None or not _is_complete(self.root / r.path):
                missing.append((e, a, b))
                continue
            rows.append(dict(event_id=e, A=a, lam=b, run_id=r.run_id, status="cached", path=r.path, wall_ms=0, message=""))
        if missing:
            raise EngineError(f"modo offline: faltan {len(missing)} corridas persistidas (ej. evento {missing[0][0]}, "
                              f"A={missing[0][1]}, lam={missing[0][2]}). Ejecutar sin --offline para simularlas.")
        return pd.DataFrame(rows)

    def _java(self, args: list[str]) -> subprocess.CompletedProcess:
        if self.offline:
            raise EngineError("modo offline: se intento invocar el motor Java")
        cmd = ["java", "-cp", self.classpath, "ar.edu.itba.sds.tp5.cli.Main", *args, "--root", str(self.root)]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode != 0:
            raise EngineError(f"El motor fallo (rc={res.returncode}): {res.stderr.strip() or res.stdout.strip()}")
        return res

    def export_events(self):
        self._java(["export-events", "--config", self.base_config])

    def request(self, tasks, log=print) -> pd.DataFrame:
        """tasks: iterable de (evento, A, lam). Devuelve DataFrame con run_id/status/path por tarea."""
        tasks = [(int(e), float(a), float(b)) for e, a, b in tasks]
        if self.offline:
            return self._request_offline(tasks)
        tag = hashlib.sha1(json.dumps(tasks).encode()).hexdigest()[:12]
        bdir = self.root / "output" / "batch"
        bdir.mkdir(parents=True, exist_ok=True)
        batch = bdir / f"batch_{tag}.json"
        batch.write_text(json.dumps({"baseConfig": self.base_config, "tasks": [list(t) for t in tasks]}))
        rel_batch = str(batch.relative_to(self.root))
        res_plan = bdir / f"plan_{tag}.tsv"
        self._java(["batch", "--config", rel_batch, "--dry-run", "--result", str(res_plan.relative_to(self.root))])
        plan = _rename(pd.read_csv(res_plan, sep="\t", keep_default_na=False))
        if (plan.status == "failed").any():
            bad = plan[plan.status == "failed"].iloc[0]
            raise EngineError(f"Tarea invalida ({bad.event_id}, {bad.A}, {bad.lam}): {bad.message}")
        missing = plan[plan.status == "missing"]
        if len(missing):
            log(f"[motor] {len(missing)} corridas faltantes de {len(plan)}; simulando solo esas")
            sub = [[int(r.event_id), float(r.A), float(r.lam)] for r in missing.itertuples()]
            sub_tag = hashlib.sha1(json.dumps(sub).encode()).hexdigest()[:12]
            sb = bdir / f"batch_missing_{sub_tag}.json"
            sb.write_text(json.dumps({"baseConfig": self.base_config, "tasks": sub}))
            res_run = bdir / f"run_{sub_tag}.tsv"
            t0 = time.perf_counter()
            self._java(["batch", "--config", str(sb.relative_to(self.root)), "--threads", str(self.threads),
                        "--result", str(res_run.relative_to(self.root))])
            self.last_physical_seconds = time.perf_counter() - t0
            self.total_physical_seconds += self.last_physical_seconds
            ran = _rename(pd.read_csv(res_run, sep="\t", keep_default_na=False))
            if (ran.status == "failed").any():
                bad = ran[ran.status == "failed"].iloc[0]
                raise EngineError(f"Corrida fallida ({bad.event_id}, {bad.A}, {bad.lam}): {bad.message}")
            log(f"[motor] simulacion fisica: {self.last_physical_seconds:.1f} s")
        else:
            self.last_physical_seconds = 0.0
        plan["status"] = np.where(plan.status == "missing", "ran", plan.status)
        return plan


def _rename(df: pd.DataFrame) -> pd.DataFrame:
    """Columnas del resultado del motor (amplitude_m, lambda_m) -> nombres cortos del analisis (A, lam)."""
    return df.rename(columns={"amplitude_m": "A", "lambda_m": "lam"})


def _is_complete(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            n = f.tell()
            f.seek(max(0, n - 512))
            return b"# END status=ok" in f.read()
    except OSError:
        return False


def _errors_for_file(args):
    run_path, ev_path = args
    from .readers import read_event
    ev = read_event(ev_path)
    run = read_run(run_path)
    r = event_errors(ev, run)
    out = []
    for aid in ev.agents:
        out.append((r["per_agent"][aid][0], r["per_agent"][aid][1]))
    return out


class ErrorStore:
    """Cache de errores DERIVADOS (output/derived/errors.tsv). La evidencia primaria sigue siendo el TXT de la corrida."""

    def __init__(self, root, events_dir=None):
        self.root = Path(root).resolve()
        self.path = self.root / "output" / "derived" / "errors.tsv"
        self.events_dir = Path(events_dir) if events_dir else self.root / "output" / "events"
        self.events = read_events_dir(self.events_dir)
        self._df = None
        if self.path.exists():
            df = pd.read_csv(self.path, sep="\t", dtype={"run_id": str})
            self._df = df[df.version == ERRORS_VERSION] if "version" in df else None
        if self._df is None:
            self._df = pd.DataFrame(columns=["run_id", "E1_a", "E2_a", "E1_b", "E2_b", "version"])
        self.postprocess_seconds = 0.0

    def get(self, plan: pd.DataFrame, workers: int = 2, log=print) -> pd.DataFrame:
        """plan: DataFrame del Engine.request. Devuelve event_id,A,lam,E1,E2 (error del evento, 2 peatones)."""
        t0 = time.perf_counter()
        known = set(self._df.run_id)
        todo = plan[~plan.run_id.isin(known)].drop_duplicates("run_id")
        if len(todo):
            log(f"[errores] calculando {len(todo)} corridas desde los TXT persistidos")
            jobs = [(self.root / r.path, self.events[int(r.event_id)].path) for r in todo.itertuples()]
            if workers > 1 and len(jobs) > 50:
                with ProcessPoolExecutor(max_workers=workers) as ex:
                    res = list(ex.map(_errors_for_file, jobs, chunksize=32))
            else:
                res = [_errors_for_file(j) for j in jobs]
            rows = [dict(run_id=r.run_id, E1_a=v[0][0], E2_a=v[0][1], E1_b=v[1][0], E2_b=v[1][1], version=ERRORS_VERSION)
                    for r, v in zip(todo.itertuples(), res)]
            self._df = pd.concat([self._df, pd.DataFrame(rows)], ignore_index=True)
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self._df.to_csv(self.path, sep="\t", index=False)
        d = self._df.drop_duplicates("run_id").set_index("run_id")
        out = plan[["event_id", "A", "lam", "run_id"]].copy()
        out["E1"] = (d.loc[out.run_id, "E1_a"].values + d.loc[out.run_id, "E1_b"].values) / 2.0
        out["E2"] = (d.loc[out.run_id, "E2_a"].values + d.loc[out.run_id, "E2_b"].values) / 2.0
        self.postprocess_seconds += time.perf_counter() - t0
        return out.drop(columns="run_id").reset_index(drop=True)


def evaluate(engine: Engine, store: ErrorStore, points, event_ids, log=print) -> pd.DataFrame:
    """Errores por evento para cada (A, lam) de 'points' (genera solo las corridas que falten)."""
    tasks = [(e, A, lam) for (A, lam) in points for e in event_ids]
    plan = engine.request(tasks, log=log)
    return store.get(plan, log=log)
