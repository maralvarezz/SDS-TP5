"""Lectores de los TXT: trayectorias simuladas (formato sds-tp5-trajectory v1) y eventos experimentales
(sds-tp5-event v1). Validan esquema, columnas y marcador de corrida completa."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

TRAJ_FORMAT = "sds-tp5-trajectory v1"
TRAJ_COLUMNS = ["event_id", "frame", "time_s", "agent_id", "x_m", "y_m", "vx_m_s", "vy_m_s", "radius_m", "theta_rad"]
EVENT_FORMAT = "sds-tp5-event v1"
EVENT_COLUMNS = ["frame", "t_s", "agent_id", "side", "x_m", "y_m"]


class FormatError(ValueError):
    pass


@dataclass
class AgentTrack:
    """Trayectoria de un agente (experimental o simulada). t en segundos desde el inicio del evento."""
    agent_id: str
    t: np.ndarray
    frame: np.ndarray
    x: np.ndarray
    y: np.ndarray
    vx: np.ndarray | None = None
    vy: np.ndarray | None = None
    theta: np.ndarray | None = None
    side: str | None = None
    meta: dict = field(default_factory=dict)  # x0, y0, vx0, vy0, vd, target_x, target_y


@dataclass
class Run:
    path: Path
    header: dict
    event_id: int
    p1: float
    p2: float
    agents: dict  # agent_id -> AgentTrack (orden de aparicion)


@dataclass
class EventData:
    path: Path
    header: dict
    event_id: int
    fps: float
    start_frame: int
    end_frame: int
    agents: dict  # agent_id -> AgentTrack (experimental)


_AGENT_RE = re.compile(r"(\w+)=([^\s]+)")


def _parse_header(lines: list[str]) -> dict:
    h: dict = {"agents": [], "warnings": []}
    for ln in lines:
        body = ln[1:].strip()
        if ":" not in body:
            continue
        k, v = body.split(":", 1)
        k, v = k.strip(), v.strip()
        if k == "agent":
            h["agents"].append(dict(_AGENT_RE.findall(v)))
        elif k == "warning":
            h["warnings"].append(v)
        else:
            h[k] = v
    return h


def _read_table(path: Path, columns: list[str], fmt: str, require_end: bool):
    head: list[str] = []
    rows: list[list[str]] = []
    end_seen = False
    with open(path, "r", encoding="utf-8") as f:
        for ln in f:
            if ln.startswith("#"):
                head.append(ln.rstrip("\n"))
                if ln.startswith("# END status=ok"):
                    end_seen = True
            elif ln.strip():
                rows.append(ln.split())
    h = _parse_header(head)
    if h.get("format") != fmt:
        raise FormatError(f"{path}: formato '{h.get('format')}' != '{fmt}'")
    if h.get("columns", "").split() != columns:
        raise FormatError(f"{path}: columnas inesperadas: {h.get('columns')}")
    if require_end and not end_seen:
        raise FormatError(f"{path}: corrida incompleta (falta '# END status=ok')")
    if not rows:
        raise FormatError(f"{path}: sin datos")
    if any(len(r) != len(columns) for r in rows):
        raise FormatError(f"{path}: fila con numero de columnas incorrecto")
    return h, rows


def read_run(path) -> Run:
    path = Path(path)
    h, rows = _read_table(path, TRAJ_COLUMNS, TRAJ_FORMAT, require_end=True)
    ids = [r[3] for r in rows]
    num = np.array([[float(r[i]) for i in (0, 1, 2, 4, 5, 6, 7, 9)] for r in rows])
    agents: dict = {}
    for aid in dict.fromkeys(ids):
        m = np.array([i == aid for i in ids])
        a = num[m]
        agents[aid] = AgentTrack(agent_id=aid, t=a[:, 2], frame=a[:, 1], x=a[:, 3], y=a[:, 4],
                                 vx=a[:, 5], vy=a[:, 6], theta=a[:, 7])
    for ainfo in h["agents"]:
        agents[ainfo["id"]].side = ainfo.get("side")
        agents[ainfo["id"]].meta = {k: float(v) for k, v in ainfo.items() if k not in ("id", "side")}
    return Run(path=path, header=h, event_id=int(h["event_id"]), p1=float(h["p1_horizon_s"]),
               p2=float(h["p2_clearance_m"]), agents=agents)


def read_event(path) -> EventData:
    path = Path(path)
    h, rows = _read_table(path, EVENT_COLUMNS, EVENT_FORMAT, require_end=False)
    agents: dict = {}
    ids = [r[2] for r in rows]
    for aid in dict.fromkeys(ids):
        sel = [r for r in rows if r[2] == aid]
        agents[aid] = AgentTrack(
            agent_id=aid, side=sel[0][3],
            frame=np.array([float(r[0]) for r in sel]), t=np.array([float(r[1]) for r in sel]),
            x=np.array([float(r[4]) for r in sel]), y=np.array([float(r[5]) for r in sel]))
    for ainfo in h["agents"]:
        agents[ainfo["id"]].meta = {k: float(v) for k, v in ainfo.items() if k not in ("id", "side")}
    return EventData(path=path, header=h, event_id=int(h["event_id"]), fps=float(h["fps"]),
                     start_frame=int(h["start_frame"]), end_frame=int(h["end_frame"]), agents=agents)


def read_events_dir(events_dir) -> dict:
    """event_id -> EventData para todos los event_XX.txt del directorio."""
    out = {}
    for p in sorted(Path(events_dir).glob("event_*.txt")):
        e = read_event(p)
        out[e.event_id] = e
    if not out:
        raise FileNotFoundError(f"No hay event_*.txt en {events_dir}. Ejecutar: java -jar ... export-events")
    return out
