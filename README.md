# SdS TP5 — Problema 1: calibración de una capa de elusión (SFM sin fuerza social)

Alcance **exclusivo**: Problema 1 del enunciado (`docs/TP5_Enunciado.pdf`, pp. 1–3). El Problema 2 no se implementa.
Reglas de trabajo del repo: `AGENTS.md`.

## Flujo

```text
input/*.json + docs/ (dataset)  ->  simulation/ (Java)  ->  output/runs/evNN/<run_id>.txt  ->  viz/ (Python, offline)
```

```bash
# 1) motor (desde simulation/)
mvn test && mvn package                       # genera simulation/target/sds-tp5.jar
# 2) desde la raíz del repo
java -jar simulation/target/sds-tp5.jar export-events --config input/base_config.json   # eventos experimentales recortados
java -jar simulation/target/sds-tp5.jar run --config input/base_config.json              # una corrida (con cache)
python viz/run_analysis.py all                # grilla + poblacional + individual + CV + gráficos + animaciones
python viz/run_analysis.py all --offline      # idem sobre TXT existentes, SIN invocar Java (falla si falta alguna corrida)
python -m pytest viz/tests                    # pruebas de métricas/experimentos
```

Python (`numpy`, `pandas`, `matplotlib`; `ffmpeg` opcional para mp4, si no hay genera gif). Si el jar no está en
`simulation/target/`, definir `SDS_TP5_CLASSPATH`.

## Decisiones

**Modelo: SFM sin fuerza social** (Helbing, Farkas & Vicsek 2000, *Nature* 407:487), elegido porque su dinámica es la más simple
(tendencia a la velocidad deseada + contacto granular) y no oculta la contribución de la capa de elusión:

`m dv_i/dt = m (v_d e_i − v_i)/τ + Σ_j [ k g(r_ij − d_ij) n_ij + κ g(r_ij − d_ij) (Δv_ji·t_ij) t_ij ]`, `g(x)=max(x,0)`.
Parámetros (config, no fijos en código): `m = 80 kg`, `τ = 0.5 s`, `k = 1.2·10⁵ kg s⁻²`, `κ = 2.4·10⁵ kg m⁻¹ s⁻¹`
(valores del paper, verificados contra arXiv cond-mat/0009448), `r = 0.25 m` (consigna). Sin paredes (la consigna no las define).
Integración: Velocity Verlet con `dt_eff = (1/fps)/n`, `n` entero → cada instante experimental `t_k` cae **exacto** en un paso y
en una salida (sin interpolar). `dt` pedido = 1e-4 s (estabilidad del contacto: `κ/m·dt < 2`). Se guardan 3 estados por intervalo experimental.

**Capa de elusión** (hipótesis de diseño propia; la consigna no fija la ley). Parámetros: `p1 = T` horizonte de anticipación [s],
`p2 = D` distancia de paso de seguridad centro-a-centro [m]. Para el agente *i* con vecino *j*, dado un rumbo candidato `u = v_d·e`:

- posición relativa `s(t) = Δr + (v_j − u) t`, `Δr = r_j − r_i`; si hay acercamiento (`t* = −Δr·w/|w|² > 0`, `w = v_j − u`),
  distancia mínima prevista `d(u) = |Δr + w·min(t*, T)|`. **Conflicto** ⇔ `d(u) < D`.
- sin conflicto con el rumbo al target final → se conserva el target final.
- con conflicto → se busca el **menor giro** `|θ|` de `e_f` (pasos de 1°, alternando lados; a igual `|θ|` se elige el lado opuesto al vecino,
  alineado ⇒ horario) tal que `R(θ) e_f` no tenga conflicto. **Target temporal**: `P_t = r_i + L R(θ) e_f`, `L = |P_final − r_i|`.
- se reevalúa cada 0.01 s; `θ → 0` continuamente al salir del conflicto y el agente retoma el target final (sin umbrales extra ni oscilación).
- la capa **solo** escribe el target; no agrega fuerzas ni toca el SFM. Parámetros numéricos no calibrados: paso angular, período de reevaluación, tolerancia de llegada (config).

**Datos de cada evento** (reglas propias donde la consigna no especifica):
ventana = frames comunes de ambos peatones dentro del intervalo del LEEME; inicio = primer frame con `K=8` muestras consecutivas en ambos;
fin = último frame común; posición inicial y target = muestras experimentales crudas; velocidad inicial = pendiente de regresión lineal de las
primeras `K` muestras; `v_d` = promedio de `|Δr|/Δt` entre frames adyacentes (rapidez media). Los huecos (evento 1, peatón 36: 32 frames) se respetan:
solo se usan frames existentes.

## Ambigüedades y hallazgos de datos (a verificar por el equipo)

- **Evento 2, ID `V?`**: no se inventó. `input/base_config.json` lo resuelve como `1000` (`idOverrides.2.left`) porque es la única persona del dataset
  con `markerID=-1`, sale por la izquierda en esa ventana y la distancia mínima recalculada (0.884 m) coincide con el LEEME (0.88). **Pendiente de verificación**;
  quitar el override excluye el evento (el motor falla con mensaje claro). Sensibilidad: `python viz/run_analysis.py analyze --exclude 2 --tag analysis_excl_ev2`.
- **Evento 4**: el peatón 10 tiene una muestra aislada en el frame 20527 y luego un hueco hasta 20544 → el inicio se desplaza a 20544 (advertencia en el header).
- Las distancias mínimas recalculadas coinciden con el LEEME en los 19 eventos (tolerancia 0.011 m); el LEEME no se usa como resultado del simulador.
- Los peatones comienzan casi quietos y muchos recorren primero un tramo lateral (en y) antes de avanzar: un SFM que apunta en línea recta al target no lo reproduce
  (p. ej. eventos 8, 9, 11, 13, 14, 19). Domina el error poblacional y hace que el óptimo (p1,p2) sea poco identificable (ver informe).

## Reproducibilidad y caché

`run_id` = SHA-256 (16 hex) de versión del algoritmo + checksums de dataset y LEEME + IDs resueltos + ventana + configuración efectiva normalizada (sin rutas).
Un `.txt` solo es cache hit si termina con `# END status=ok` (escritura a `.tmp` + rename atómico). `output/manifest.tsv` indexa las corridas.
Python pide corridas al motor en modo `batch` (plan `--dry-run` → simula solo las faltantes). Errores derivados: `output/derived/errors.tsv`.
La búsqueda de óptimos (poblacional, individual y validación cruzada) usa la **misma grilla** (14 valores de `p1` × 27 de `p2`), definida en `input/analysis_config.json`.
Semillas y particiones de validación cruzada: `output/<tag>/cross_validation_partitions.json`.

## Entrega

ZIP de código = solo `simulation/` (fuentes, `pom.xml`): `cd simulation && zip -r ../SdS_TP5_2026Q2GXXCSS_Codigo.zip pom.xml src` (sin `target/`).
