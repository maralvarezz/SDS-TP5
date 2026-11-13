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
java -jar simulation/target/sds-tp5.jar export-events --config input/base_config.json   # eventos experimentales (ventana limpia)
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

**Capa de elusión continua** (hipótesis de diseño propia; la consigna no fija la ley; especificación: `docs/ELUSION_CONTINUA.md`).
Exactamente **dos parámetros calibrables**: `A` amplitud de elusión [m] y `λ` alcance espacial [m] (ambos finitos y `> 0`).
Para el agente *i* y el otro peatón *j*, con `d = |r_j − r_i|` (distancia actual entre centros) y `e_f` la dirección unitaria al target final:

`P_temp = r_i + L₀ e_f + A·exp(−d/λ)·w(s)·n`, con `n = (e_y, −e_x)` (derecha propia respecto del avance)

- `a(d) = A·exp(−d/λ)` es un **offset del target**, no una separación garantizada. No hay umbral binario de conflicto ni búsqueda de ángulo: la respuesta es continua en `d`.
  El ángulo de desvío resultante es `|θ| = arctan(a·w/L₀)` (se guarda como `theta_rad` en el TXT; es una cantidad derivada, no un parámetro).
- **`L₀ = 1.0 m`**: punto de avance fijo, constante de diseño **no calibrable** e idéntica en todos los experimentos (se usa fijo, y no la distancia restante a la meta,
  para que el ángulo no dependa de cuánto falta). Con `A ≲ 2 m` el giro máximo es ≈ 63°.
- **Fin de encuentro `w(s)`** (regla geométrica determinista, sin parámetros nuevos): `s = (r_j − r_i)·e_f`. Si el otro está adelante (`s ≥ 0`) `w = 1` y rige exactamente
  `a(d)`; cuando ya pasó, `w` baja suavemente con `smoothstep` a lo largo de `ℓ = L₀`, y vale 0 desde `s ≤ −ℓ` (el agente vuelve a apuntar al target final sin saltos de dirección).
- **Lado de esquiva**: siempre a la derecha propia (convención "keep right"). Dos peatones frontales toman lados opuestos del mapa. Es una **hipótesis del modelo**: las
  trayectorias experimentales muestran esquivas a ambos lados (p. ej. eventos 6, 8, 12), que esta convención no puede reproducir.
- La capa **solo** escribe el target (cambia la dirección de la velocidad deseada); no agrega fuerzas, no toca el SFM ni el módulo de `v_d`. Se reevalúa cada 0.01 s.
  Parámetros numéricos no calibrados (config): período de reevaluación y tolerancia de llegada.
- Referencia "sin elusión": `input/base_config_no_avoidance.json` (`avoidance.enabled = false`; `A` y `λ` se ignoran). No es una combinación de parámetros.
- Código antiguo con el criterio `T`/`D` (horizonte y distancia de paso con búsqueda de giro mínimo): **reemplazado**; no hay alias. Los resultados de esa versión
  (algoritmo `1.0.0`) quedaron en `output/old_v1_TD/` (ignorado por Git) y no son comparables con los actuales (`2.0.0`).

**Datos de cada evento** (reglas propias donde la consigna no especifica):
ventana = frames comunes de ambos peatones dentro de la **ventana limpia** (ver abajo); inicio = primer frame con `K=8` muestras consecutivas en ambos;
fin = último frame común; posición inicial y target = muestras experimentales crudas de esa ventana; velocidad inicial = pendiente de regresión lineal de las
primeras `K` muestras; `v_d` = promedio de `|Δr|/Δt` entre frames adyacentes (rapidez media).

**Limpieza de trayectorias (decisión del equipo, se aparta de la definición de la cátedra de "evento = intervalo del LEEME")**: muchos peatones arrancan
con un tramo vertical (suben desde y ≈ 0 hasta el carril central) que no es parte del cruce. `input/event_windows.json` define, por evento, una **ventana común
(intersección de las ventanas individuales)**: el inicio de cada peatón es cuando llega al carril (se descarta el tramo vertical) y su final es cuando pasa más cerca del punto
donde empezó el otro; la ventana del evento es [inicio más tardío, final más temprano] porque la simulación y las dos métricas necesitan un eje de tiempo común.
Se genera con `viz/make_event_windows.py` a partir de los eventos crudos y **no se inventa ni interpola ningún dato**. Revisados a mano:
evento 18 (peatón 26 arranca cuando termina de subir), evento 13 (las trayectorias terminan antes del inicio del otro: final = último cuadro, inicio adelantado hasta donde termina
el otro; ventana de 3.2 s), evento 1 (al peatón 36 le faltan 32 frames entre 4.26 y 6.46 s: ambos terminan antes del hueco; ventana de 4.26 s, los targets quedan a mitad del pasillo).
Con la ventana limpia, el estado inicial, el target y la duración del evento cambian respecto del LEEME; el motor registra el SHA-256 de `event_windows.json` en cada TXT y en el `run_id`.

```bash
java -jar simulation/target/sds-tp5.jar export-events --config input/base_config_raw_events.json --out output/events_raw   # ventana completa del LEEME
python viz/make_event_windows.py --plot output/ventanas                                                           # escribe input/event_windows.json
java -jar simulation/target/sds-tp5.jar export-events --config input/base_config.json                             # eventos limpios -> output/events
```

## Ambigüedades y hallazgos de datos (a verificar por el equipo)

- **Evento 2, ID `V?`**: no se inventó. `input/base_config.json` lo resuelve como `1000` (`idOverrides.2.left`) porque es la única persona del dataset
  con `markerID=-1`, sale por la izquierda en esa ventana y la distancia mínima recalculada (0.884 m) coincide con el LEEME (0.88). **Pendiente de verificación**;
  quitar el override excluye el evento (el motor falla con mensaje claro). Sensibilidad: `python viz/run_analysis.py analyze --exclude 2 --tag analysis_excl_ev2`.
- **Evento 4**: el peatón 10 tiene una muestra aislada en el frame 20527 y luego un hueco hasta 20544 → el inicio se desplaza a 20544 (advertencia en el header de la exportación cruda).
- Las distancias mínimas recalculadas coinciden con el LEEME en los 19 eventos (tolerancia 0.011 m); el LEEME no se usa como resultado del simulador.
- Los tramos verticales iniciales (eventos 6, 7, 8, 9, 11, 13, 14, 19) se eliminan con la ventana limpia (ver arriba).

**Animaciones**: usan `input/base_config_animation.json` (misma dinámica, mismos parámetros) con `animation.extendUntilArrival = true`: la corrida sigue **después del
último instante experimental** hasta que ambos centros están en su target final (a ≤ 2 cm y con rapidez < 5 cm/s) y se agregan `animation.holdSeconds = 2 s` más, para ver que
quedan parados en el centro de su destino. Son corridas distintas de las de las métricas (otro `run_id`); las métricas y la calibración **no** las usan (la consigna termina la
simulación en el último instante experimental, y la Métrica 2 usa todos los puntos simulados). El dato experimental queda congelado en su última posición.
`python3 viz/run_analysis.py animations --animation-events all` (sin `--offline` la primera vez, porque hay que simular las corridas extendidas; luego se puede usar `--offline`).

## Hallazgos de la calibración con la elusión continua (a revisar)

- **Grilla uniforme** (ejes lineales a escala): `A` de 0.05 a 1.5 m en pasos de 0.05 (30 valores) × `λ` de 1 a 30 m en pasos de 1 (30 valores) = 900 combinaciones × 19 eventos.
  Una grilla previa con `λ` hasta 16 m dejaba el mínimo en el borde; se probó hasta `λ = 100 m` y el error **sube** de nuevo, así que el óptimo es interior.
- **Figuras de la región óptima**: `poblacional_superficie_heatmap.png` muestra solo `λ ≤ 16 m` (`plots.heatmapLambdaMax` en `analysis_config.json`; recorta lo que se dibuja, la calibración usa toda la grilla), con un único punto en el óptimo; `region_optima_trayectorias_E1/E2.png` superpone, en 4 eventos (`plots.regionEvents`), la trayectoria experimental con las simuladas de todos esos pares (corridas extendidas hasta el target).
- **Calibración individual (ítem c) con λ fijo**: por decisión del equipo, a partir del ítem c se toma un solo `λ` (el óptimo poblacional de cada métrica, `λ = 13 m`) y por evento solo se calibra `A`
  (`individual.fixLambdaToPopulation` en `analysis_config.json`). El ítem b mantiene la variación de ambos parámetros porque es poblacional. La lectura literal de la consigna (par `(A_m, λ_m)` por evento) se guarda aparte
  en `individual_calibration_2d.csv`. Con `λ` fijo la ganancia de individualizar es mínima (⟨E1⟩ 0.3058 → 0.3045; ⟨E2⟩ 0.0519 → 0.0510; 16 de 19 eventos eligen `A = 0.15`), contra 0.2987 y 0.0477 con el par libre.
  La validación cruzada (ítem d) sigue calibrando `(A, λ)` solo con los eventos de entrenamiento, para no filtrar información de los de validación (el `λ` de (b) se eligió con los 19).
- **Óptimo poblacional** (ambas métricas coinciden): `A = 0.15 m, λ = 13 m` (⟨E1⟩ = 0.306 m², ⟨E2⟩ = 0.052 m²). Con `A = 0.15`, ⟨E1⟩ pasa de 0.306 (`λ = 13`) a 0.319 (`λ = 30`).
- **El valle es muy plano**: para `λ ≳ 10` el error casi no cambia (menos de 1 error estándar ≈ 0.046), y el conjunto de indiferencia (`indifference_set_*.csv`: pares dentro de 1 error estándar del mínimo)
  tiene 51 pares (E1) y 34 pares (E2), con `A` ∈ [0.15, 0.6] y `λ` ∈ [2, 30]. Con `λ` grande `a(d)` casi no depende de `d` (giro chico y casi constante a la derecha), de modo que los datos
  **no identifican bien** la dependencia con la distancia. Hay que informar esa región de indiferencia, no solo el mínimo.
- **Validación física** (ítem 12 de la especificación): la fórmula no evita colisiones por construcción. En el óptimo la distancia mínima centro a centro es ≥ 0.504 m en los 19 eventos (sin solapamiento, 2r = 0.5 m);
  en la grilla completa ~9 % de las corridas tienen solapamiento, casi todas con `A` chica (mediana 0.1 m: la elusión no alcanza para evitar el cruce).
- La referencia sin elusión da `⟨E1⟩ = 2.21 m²`, `⟨E2⟩ = 1.29 m²` (contra ~0.31 y ~0.053 en los óptimos).

## Reproducibilidad y caché

`run_id` = SHA-256 (16 hex) de versión del algoritmo + checksums de dataset y LEEME + IDs resueltos + ventana + configuración efectiva normalizada (sin rutas).
Un `.txt` solo es cache hit si termina con `# END status=ok` (escritura a `.tmp` + rename atómico). `output/manifest.tsv` indexa las corridas.
Python pide corridas al motor (tareas `[evento, A, lambda]`) en modo `batch` (plan `--dry-run` → simula solo las faltantes). Errores derivados: `output/derived/errors.tsv`.
La búsqueda de óptimos (poblacional, individual y validación cruzada) usa la **misma grilla** (30 valores de `A` ∈ [0.05, 1.5] m × 30 de `λ` ∈ [1, 30] m, uniforme), definida en `input/analysis_config.json`.
Semillas y particiones de validación cruzada: `output/<tag>/cross_validation_partitions.json`.

## Entrega

ZIP de código = solo `simulation/` (fuentes, `pom.xml`): `cd simulation && zip -r ../SdS_TP5_2026Q2GXXCSS_Codigo.zip pom.xml src` (sin `target/`).
