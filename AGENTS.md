# AGENTS.md — SdS TP5: Dinámica peatonal

## 1. Rol, autoridad y objetivo

Actuás como agente implementador y auditor del **TP5 de Simulación de Sistemas (2026, segundo cuatrimestre)**. Tu responsabilidad es construir desde cero, mantener y verificar un sistema reproducible para simular, persistir trayectorias y generar observables **sin volver a ejecutar simulaciones cuando alcanza con reprocesar salidas existentes**.

**Fuente de verdad:** `docs/TP5_Enunciado.pdf`. Consultá el PDF antes de implementar o modificar reglas del modelo, observables o experimentos. También son material de referencia los archivos TXT provistos en `docs/`, incluidos `LEEME_elusion1contra1.txt` y `elusion_1_contra_1.txt`. No sustituyas requisitos de la cátedra por suposiciones ni trates una propuesta de implementación como exigencia del enunciado. Si un parámetro físico no está especificado, consultar las referencias citadas por la consigna; documentar fuente, valor, unidades y motivo. Si falta información imprescindible, dejarla explicitada en lugar de inventarla.

**Alcance definitivo y exclusivo:** implementar únicamente el **Problema 1: calibración de una capa de elusión con trayectorias experimentales** (páginas 1 a 3 del PDF). El **Problema 2 está expresamente fuera de alcance**: no diseñar, implementar, probar ni documentar funcionalidad de evacuación, esquemas EDMD/TSDMD o modelos híbridos. Si surge una propuesta vinculada al Problema 2, descartarla por estar fuera del alcance acordado.

## 2. Arquitectura obligatoria

La raíz del repositorio tendrá exactamente estas carpetas funcionales:

```text
repo/
├── AGENTS.md
├── .gitignore
├── docs/                    # PDF de consigna, papers y TXT recibidos
│   ├── TP5_Enunciado.pdf
│   ├── LEEME_elusion1contra1.txt
│   └── elusion_1_contra_1.txt
├── input/                   # Configuraciones JSON o TXT y manifiestos de experimentos
├── output/                  # Salidas de simulación y derivados; NO versionar contenido
├── simulation/              # ÚNICO proyecto Maven; motor e I/O Java
│   ├── pom.xml
│   └── src/
│       ├── main/java/
│       └── test/java/
└── viz/                     # Análisis, observables, calibración y animaciones en Python
```

- `simulation/` contiene **todo el motor físico en Java**, su entrada/configuración, su CLI y las pruebas del motor. El `pom.xml` está en `simulation/`; no crear otro motor paralelo ni mezclar scripts de visualización con Maven.
- `viz/` es **exclusivamente Python** para lectura de archivos persistidos, observables, agregaciones, gráficos, calibración y animaciones. Puede orquestar ejecuciones del motor Java para parámetros *aún no simulados*, pero nunca reimplementa su física.
- `input/` contiene configuraciones reproducibles (JSON preferido; TXT también permitido) y, si corresponde, listados de experimentos, semillas y grillas de parámetros. Los datos experimentales originales se conservan en `docs/`; la aplicación debe poder referenciarlos por ruta.
- `output/` guarda los `.txt` generados por el motor y los derivados (observables, figuras, animaciones, informes, etc.). Debe estar **íntegramente ignorado por Git**. Añadir al `.gitignore` la regla ` /output/ ` **sin los espacios**, es decir `/output/`. No versionar resultados ni archivos grandes.
- `docs/` conserva los materiales suministrados, sin editar los datos experimentales originales. Un archivo derivado o filtrado debe ir a `output/` e incluir su procedencia.
- No crear directorios de primer nivel adicionales sin necesidad justificada y aprobación del equipo.

### Flujo de datos innegociable

```text
input/ (JSON o TXT) + docs/ (dataset experimental)
                     |
                     v
         simulation/ (Java + Maven)
                     |
                     v
            output/.../*.txt
                     |
                     v
          viz/ (Python, offline)
            |             |
            v             v
        observables   plots/animations
```

**Regla central:** una simulación produce un registro reutilizable del estado temporal. Si se quiere cambiar un gráfico, calcular otro observable, probar otra agregación, cambiar el estilo de una animación o repetir una evaluación estadística basada en las mismas trayectorias, **leer el TXT existente y no correr el motor otra vez**. Sí corresponde simular cuando cambian parámetros que afectan la dinámica, condiciones iniciales, algoritmo/modelo, semilla o resolución necesaria para un observable que no puede reconstruirse de los datos almacenados.

## 3. Separación de responsabilidades

### `simulation/` (Java)

Implementar módulos/clases con responsabilidades claras, aunque los nombres finales pueden adaptarse:

1. **Configuration:** lectura y validación de configuración JSON/TXT, parámetros físicos, integración, salida, identificador de evento y rutas.
2. **ExperimentalDataReader:** lectura estricta del dataset experimental; agrupación por persona y frame, manejo de intervalos, huecos y metadata.
3. **Model:** entidades peatón, posición, velocidad, radio, target final, velocidad deseada y estado temporal.
4. **Integrator/SimulationEngine:** evolución física con paso `dt` propio, determinismo y condiciones de finalización.
5. **AvoidanceLayer:** evaluación de conflicto futuro y target temporal, con exactamente dos parámetros ajustables `p1` y `p2`, para el Problema 1.
6. **TextOutputWriter:** persistencia incremental de estados e información de ejecución, sin agregar cálculos de observables del análisis.
7. **CLI:** ejecutar por ruta de configuración; errores claros y códigos de salida no cero ante fallos.

No crear dependencias circulares. Los módulos científicos no deben depender de la presentación, visualización, rutas absolutas o un IDE. Priorizar tipos/estructuras claras y pruebas antes de optimización prematura.

### `viz/` (Python)

Separar conceptualmente:

1. **Readers:** cargar salidas del motor y trayectorias experimentales, validar esquema y unidades.
2. **Observables:** calcular métricas sobre las trayectorias ya persistidas.
3. **Calibration:** proponer combinaciones de parámetros, identificar corridas faltantes, invocar Java solo para esas corridas y reutilizar resultados existentes.
4. **Experiments:** agregaciones poblacionales, individuales, particiones entrenamiento/validación y comparaciones.
5. **Plots:** curvas y superficies con ejes, unidades, leyendas y referencias.
6. **Animations:** representar, de modo independiente y offline, las trayectorias del TXT y los datos experimentales.

El código Python no debe modificar ningún resultado físico para hacerlo parecer más cercano al dato experimental. Todo suavizado o interpolación para análisis debe explicitarse.

## 4. Contrato de configuración y salida

### Input

- Aceptar **JSON o TXT** como configuración. Diseñar un único modelo interno de configuración para que ambos formatos tengan la misma semántica.
- Ejemplo orientativo de campos, sujetos al modelo elegido: `model`, `eventId`, `p1`, `p2`, `dt`, `outputDt`, `dataPath`, `outputPath`, `seed`, `physicalParameters`.
- Validar valores, unidades, rutas, consistencia temporal y ausencia de parámetros requeridos. **No fijar en el código parámetros que razonablemente deben poder variarse desde configuración**.
- Registrar en metadatos la configuración efectiva, sus defaults justificables, modelo, versión y semilla. Para parámetros tomados de papers, conservar una referencia a la fuente.
- No asumir que los identificadores de persona son consecutivos, que todos los frames existen o que todas las pasadas duran lo mismo. El README de datos incluye una identificación ambigua (`V?`): **no inventar un ID**; marcarlo para verificación.

### Output de simulación

- El artefacto canónico es un **archivo de texto `.txt` legible por máquina**, independiente de Python y del motor.
- Registrar al menos `event_id`, `time_s` o `frame`, `agent_id`, `x_m`, `y_m`, `vx_m_s`, `vy_m_s` y, si aplica al modelo, `radius_m`; preservar suficientes datos para recalcular observables y renderizar animaciones.
- Encabezado con esquema/formato, `dt`, intervalo real entre estados persistidos, unidades, parámetros, semilla, procedencia de datos y rango temporal. Puede consistir en comentarios `#` seguidos de columnas separadas por espacios o tabulaciones.
- Usar tiempo físico consistente: no confundir el frame experimental con el índice interno de integración.
- `dt` físico e intervalo de guardado (`outputDt`/`dt2`) son conceptos distintos. El modelo avanza con `dt` y guarda estados según una frecuencia configurable compatible con el análisis; en CPM `dt` pertenece a las reglas del modelo y **no se cambia arbitrariamente para acelerar el cálculo** (PDF, p. 1).
- Para la **Métrica 1** deben existir posiciones simuladas en los mismos instantes experimentales `t_k` (directamente, o mediante una interpolación temporal definida, documentada y probada). Un `dt2` grueso no debe arruinar la comparación.
- No sobrescribir silenciosamente archivos. Una corrida se identifica por combinación de modelo, dataset/evento, parámetros físicos, `p1`/`p2`, esquema temporal, semilla y versión relevante del algoritmo. Mantener un manifiesto o metadatos para detectar cache hits de manera confiable.
- Si el TXT es enorme, usar escritura incremental y formato compacto **sin perder información indispensable**. No elegir solo el último estado como output.

## 5. Requisitos específicos del Problema 1, tomados del PDF

**Fuente:** `docs/TP5_Enunciado.pdf`, sección «Problema 1: Calibración de una capa de elusión con trayectorias experimentales», pp. 1–3. Las demás secciones del PDF no amplían el alcance de la implementación.

### Modelo y capa de elusión

- Elegir **SFM sin fuerza social o CPM**. Documentar explícitamente la elección y fundamentarla; no implementar una mezcla no solicitada.
- Ambos modelos por sí solos no anticipan colisiones. Crear una capa de elusión con **exactamente dos parámetros a ajustar** (`p1`, `p2`).
- La capa **solo puede cambiar la velocidad deseada mediante un target temporal** cuando detecta una posible colisión futura. Al desaparecer el conflicto, el peatón retoma su target final.
- La capa **no puede agregar fuerzas ni alterar las reglas del modelo base**. Definir matemáticamente criterio de detección, construcción del target temporal, parámetros y retorno al target original. No elegir fórmulas o umbrales físicos arbitrarios sin documentarlos como hipótesis de diseño.

### Condiciones iniciales y finales por evento

- Simular **dos agentes** por evento.
- Inicializar **posición y velocidad de cada agente** con los valores experimentales en el **instante inicial del evento**. Si la velocidad no está dada directamente, documentar cómo se estima de la trayectoria y validar el método.
- El target final de cada agente es la **última posición experimental** de su trayectoria.
- El **módulo de velocidad deseada `vd`** es la **rapidez media experimental del agente**; no confundir rapidez media (promedio de módulos) con módulo de velocidad media (desplazamiento/tiempo).
- Terminar la simulación en el **último instante experimental** de ese evento.
- Si se usa SFM: radio `r = 0.25 m`. Si se usa CPM: `rmin = 0.10 m`, `rmax = 0.25 m`.
- Para otros parámetros del modelo, recurrir a los papers referenciados en el PDF y registrar las elecciones.

### Datos experimentales

- `docs/elusion_1_contra_1.txt` tiene encabezado `# framerate: 15.0192 fps` y columnas `id frame x/m y/m markerID`. Usar el framerate declarado como fuente de conversión temporal, salvo evidencia fundamentada que indique otro criterio.
- `docs/LEEME_elusion1contra1.txt` documenta **19 pasadas** de cruce 1 contra 1, con parejas de personas, intervalos de frames y distancias mínimas reportadas. Sus intervalos pueden superponerse: no suponer que cada intervalo representa un bloque aislado de filas del archivo ni que los frames son continuos para cada individuo.
- Tratar el README como guía de selección de pares y validar su correspondencia con las trayectorias; reportar inconsistencias o identificaciones ambiguas, no corregirlas silenciosamente.
- No usar valores de distancias mínimas del README como resultados del simulador ni como sustituto de las métricas de calibración.

### Observables: implementar exactamente las dos métricas requeridas

Para cada evento `m`, peatón `i` e instantes experimentales `t_k`, con `N_m` muestras:

- **Métrica 1 (iguales tiempos):**
  `E1_i = (1/N_m) * sum_k [(x_exp_i(t_k)-x_sim_i(t_k))^2 + (y_exp_i(t_k)-y_sim_i(t_k))^2]`.
- **Métrica 2 (punto más cercano):**
  `E2_i = (1/N_m) * sum_k min_j [(x_exp_i(t_k)-x_sim_i(t_j))^2 + (y_exp_i(t_k)-y_sim_i(t_j))^2]`.

Para cada métrica, **error del evento = promedio de los dos agentes**; **error poblacional = promedio de los `M` eventos**, con idénticos parámetros `p1,p2` para todos los eventos. Las métricas son **errores cuadráticos medios**, en `m²`, **no RMSE**. No mezclar la normalización ni usar distancia euclidiana sin elevar al cuadrado. La Métrica 1 penaliza geometría y tiempos; la Métrica 2, la cercanía geométrica sin exigir sincronización temporal.

### Estudios requeridos (Problema 1)

1. **Implementación y animaciones:** superponer trayectorias reales y simuladas para eventos representativos; dedicar espacio a explicar matemáticamente la capa de elusión.
2. **Calibración poblacional:** buscar el par `(p1,p2)` que minimiza **por separado** `⟨E1⟩` y `⟨E2⟩`; documentar el procedimiento, graficar la superficie/grilla o cortes con errores y comparar ambos óptimos.
3. **Calibración individual:** para cada evento, optimizar `(p1_m,p2_m)` **por separado para cada métrica**; mostrar distribución de parámetros óptimos, valores poblacionales y distribución de errores, cuantificando la mejora al individualizar.
4. **Validación cruzada:** particionar eventos aleatoriamente en entrenamiento/validación, calibrar solo en entrenamiento, evaluar en validación, repetir con distintas particiones reproducibles y comparar con la calibración poblacional.

La calibración de parámetros dinámicos **sí exige corridas físicas nuevas cuando la combinación no está cacheada**. Lo que debe evitarse son las **corridas duplicadas**, no las corridas científicamente necesarias. La comparación de métricas y sus gráficos, en cambio, se hace sobre los TXT guardados.

## 6. Reproducibilidad, experimentación y caché

- Cada configuración debe tener un identificador estable obtenido de sus valores efectivos normalizados; incorporar versión del algoritmo y fuente de datos. Nunca declarar equivalentes dos corridas diferentes solo porque comparten un nombre de archivo.
- Mantener registro de parámetros, eventos, semillas, tiempos, versión de código, rutas y estado de éxito/falla. Una corrida incompleta no debe interpretarse como cacheada.
- El análisis debe poder tomar una carpeta de TXT existentes y producir **todos** los observables y gráficos compatibles **sin invocar Java**.
- Separar los tiempos de **ejecución física** de los tiempos de **postprocesamiento**; la animación no bloquea ni forma parte de la simulación.
- Toda aleatoriedad debe aceptar una semilla reproducible. Guardar explícitamente las semillas y particiones de validación cruzada.
- Las variantes de análisis que comparten trayectoria simulada deben reutilizar **exactamente el mismo archivo**.
- No almacenar un valor de error como único resultado canónico: el TXT de posiciones y velocidades es la evidencia primaria; los errores pueden persistirse como resultados derivados.

## 7. Pruebas y criterios de aceptación

Antes de considerar terminada una funcionalidad, verificar:

- [ ] Compilación y pruebas Maven desde `simulation/` (`mvn test`, `mvn package`).
- [ ] Configuración JSON y TXT producen la misma configuración efectiva.
- [ ] Validación adecuada de errores de entrada y rutas.
- [ ] Integración temporal consistente y estados físicamente válidos según el modelo.
- [ ] Lectura correcta de personas, frames, framerate y pares de eventos, incluyendo huecos e IDs no consecutivos.
- [ ] Condiciones iniciales, target final, `vd` medio y fin de evento coinciden con la consigna.
- [ ] La elusión solo cambia el target/velocidad deseada y usa exactamente dos parámetros calibrables.
- [ ] Resultados repetibles con idéntica configuración y semilla.
- [ ] TXT contiene estados suficientes para reconstruir animaciones y **ambas** métricas sin recalcular la simulación.
- [ ] Pruebas con trayectorias sintéticas para verificar: errores cero en trayectorias idénticas; error de Métrica 1 por desfase temporal; cálculo de mínimo de Métrica 2; agregación por dos peatones y por M eventos.
- [ ] `viz/` opera únicamente con archivos persistidos en modo offline, salvo invocación explícita de calibración para corridas ausentes.
- [ ] Calibración poblacional e individual ejecutadas **por separado para cada métrica**, con resultados trazables.
- [ ] Validación cruzada sin filtración de eventos de validación hacia el entrenamiento.
- [ ] Archivos dentro de `output/` excluidos de Git (`git check-ignore`).
- [ ] Ninguna ruta absoluta de la máquina del desarrollador está codificada.

## 8. Metodología del agente y prioridades

1. **Leer la consigna y los TXT antes de programar.** Registrar que se implementa exclusivamente el Problema 1, la elección del modelo (SFM/CPM) y las ambigüedades.
2. **Preparar estructura y contratos** de archivos (configuración, salida de trayectoria, metadatos).
3. **Implementar y probar** el lector de eventos y las condiciones iniciales.
4. **Implementar y probar** el modelo base elegido, después la capa de elusión matemática del Problema 1.
5. **Persistir estados** completos en TXT y verificar que Python los lea sin dependencia del motor.
6. **Implementar observables** y tests analíticos, luego animaciones características.
7. **Implementar calibración** con caché, primero poblacional, luego individual y finalmente validación cruzada.
8. **Verificar la consigna punto por punto** antes de dar el trabajo por finalizado. Reportar de manera concisa qué falta, en qué archivo/componente y por qué incumple la consigna.

Tomar decisiones técnicas razonables sin pedir permiso por cada clase o función; **no inventar decisiones científicas** que alteren los resultados o contradigan el PDF. Preferir código simple, mantenible y verificable, sin sobreingeniería. Si no hay suficiente información para fijar la ley matemática de elusión o una constante del paper, documentar la alternativa propuesta, sus supuestos y su impacto antes de tratarla como cerrada.

## 9. Entregables y restricciones académicas

El PDF indica presentación oral de **13 minutos**, PDF de presentación y un ZIP final **solo con el código fuente del motor de simulación**, sin postprocesamiento, salidas, figuras, documentos ni historial Git. **Fecha límite indicada: 16/11/2026, 13:00**, con nombres `SdS_TP5_2026Q2GXXCSS_Presentación` y `SdS_TP5_2026Q2GXXCSS_Codigo` (reemplazar grupo y comisión). Preparar el ZIP de entrega mediante selección explícita de fuentes necesarios de `simulation/`; el repositorio de trabajo sí debe conservar `viz/`, `docs/` e infraestructura de investigación. Para la presentación, el enunciado exige enlaces explícitos a YouTube/Vimeo para animaciones, no enlaces a Google Drive.

**Definición de terminado:** el motor corre en Java/Maven desde configuración, genera TXT reutilizable, Python reconstruye análisis/animaciones sin ejecutar el motor, los experimentos exigidos por el Problema 1 están completos y los requisitos pueden rastrearse hasta páginas concretas del PDF.
