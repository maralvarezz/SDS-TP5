# ELUSIÓN CONTINUA: especificación de cambio para TP5 (Problema 1)

## 1. Objetivo y alcance

Modificar la capa de elusión existente (`ar.edu.itba.sds.tp5.avoidance.AvoidanceLayer`) para que **la respuesta sea continua en función de la proximidad entre los dos peatones**, en lugar de utilizar una decisión binaria de conflicto seguida de una búsqueda discreta del menor giro seguro.

**Implementar exclusivamente el Problema 1** del `docs/TP5_Enunciado.pdf`. Respetar `AGENTS.md` y la arquitectura del repositorio:

- `docs/`: enunciado, referencias y documentación.
- `input/`: configuraciones y datos experimentales.
- `simulation/`: motor físico en **Java/Maven**, incluyendo la nueva capa de elusión.
- `output/`: archivos de texto generados; todo su contenido ignorado por Git.
- `viz/`: postprocesamiento, observables, calibraciones, experimentos y animaciones en **Python**.

La simulación debe generar archivos `.txt` reutilizables. `viz/` debe poder calcular observables a partir de ellos sin repetir corridas, salvo cuando cambien parámetros de simulación.

La consigna exige una capa de elusión con **exactamente dos parámetros ajustables** que opere **solo mediante un target temporal**, modificando así la dirección de velocidad deseada. **No agregar fuerzas, no modificar las reglas del SFM y no implementar el Problema 2.**

## 2. Concepto del nuevo modelo

Los parámetros calibrables pasan a ser:

| Parámetro | Nombre | Unidad | Efecto |
|---|---|---|---|
| `A` | Amplitud de elusión | m | Escala máxima del desplazamiento lateral del target temporal. Un valor mayor genera maniobras más marcadas. |
| `lambda` (`λ`) | Alcance o sensibilidad espacial | m | Controla qué tan lejos se percibe al otro peatón. Un valor mayor produce desviaciones apreciables a distancias mayores. |

Ambos deben ser **estrictamente positivos** y configurables. Ya **no se calibran** `T` (horizonte de predicción) ni `D` (umbral de seguridad). No se debe conservar una decisión `conflict = predictedClearance < D` como disparador de la elusión.

La magnitud del desplazamiento lateral en función de la distancia actual entre centros es:

\[
 d_{ij}=\|\mathbf r_j-\mathbf r_i\|,
 \qquad
 a(d_{ij})=A\exp(-d_{ij}/\lambda).
\]

**Interpretación:** `a(d)` es un *offset del target*, **no** la separación real garantizada entre peatones. Para todo `d` finito y `A>0`, la función es positiva; no hay un umbral espacial binario para activarla. El modelo **no garantiza** automáticamente que los peatones nunca colisionen: se debe validar.

## 3. Construcción del target temporal

Para cada agente `i`, en cada actualización:

1. Calcular el vector al destino real: `f = finalTarget_i - position_i`.
2. Si `|f| <= arrivalTolerance`, conservar la lógica de llegada y desactivar el target temporal.
3. Calcular la dirección al destino: `e = f / |f|`.
4. Calcular `d = hypot(x_j - x_i, y_j - y_i)` y `a = A * exp(-d / lambda)`.
5. Elegir una dirección lateral unitaria `n` perpendicular a `e`, coherente durante el encuentro.
6. Calcular un punto de avance `base = r_i + L0 * e`.
7. Calcular el target temporal: `P_temp = base + a * n`.
8. Entregar ese target al SFM existente. La velocidad deseada debe seguir teniendo el módulo prescrito por el modelo y orientar su dirección hacia `P_temp`.

Fórmula:

\[
\boxed{\mathbf P_{temp,i}=\mathbf r_i+L_0\hat{\mathbf e}_{f,i}
+A\exp(-d_{ij}/\lambda)\hat{\mathbf n}_i}
\]

Con `L0>0` **constante fija de diseño, documentada y no calibrable**. Evitar usar directamente `P_final + a*n` porque el ángulo inducido dependería de la distancia restante hasta la meta. Para el esquema con `L0` constante, el ángulo de desviación es:

\[
 |\theta|=\arctan\left(\frac{A\exp(-d/\lambda)}{L_0}\right).
\]

**Decisión de implementación sobre L0:** elegir y documentar un valor fijo razonable y conservarlo idéntico en todos los experimentos. No exponerlo como tercer eje de búsqueda ni optimizarlo contra los datos. Si el sistema ya tiene una escala geométrica fija adecuada, reutilizarla y justificarlo. El valor concreto no se impone aquí porque no está definido por la conversación ni por la consigna.

### Elección del lado de evasión

Para el **Problema 1 (dos peatones)**, usar inicialmente la convención determinista de **esquivar hacia la derecha propia respecto de la dirección de avance**, de modo que:

\[
 \hat{\mathbf n}_i=(e_{iy},-e_{ix}).
\]

Dos peatones que vienen de frente tienen direcciones opuestas y por tanto sus respectivas derechas son opuestas en el sistema global; esto ayuda a que se separen. Mantener la convención estable durante el cruce para evitar cambios de lado. Documentar que es una hipótesis del modelo y contrastarla con las trayectorias experimentales, que pueden mostrar esquivas a ambos lados.

**Importante:** no confundir `n` con una fuerza de repulsión. El vector `n` solo determina dónde se ubica el target temporal.

## 4. Un problema físico que hay que manejar: volver al target final

Una dependencia basada **solo** en la distancia euclidiana hace que, aun cuando el otro peatón ya haya pasado, siga existiendo un offset lateral. Eso puede provocar un retorno tardío o una trayectoria extraña. La solución debe preservar **solo dos parámetros ajustables**.

Implementar una lógica geométrica **determinista y no calibrable** para distinguir encuentros relevantes de encuentros terminados. Recomendación concreta:

- Calcular la proyección longitudinal `s = (r_j - r_i) · e`.
- Mientras el otro agente esté adelante (`s > 0`), aplicar el desplazamiento continuo `a(d)`.
- Cuando el encuentro quede atrás, iniciar una transición suave del offset hacia cero, **sin introducir un tercer parámetro ajustable**. No apagar abruptamente `hasTemporalTarget` si ello produce un salto en dirección.
- La transición puede basarse en una función geométrica continua ligada a `s` y una escala fija documentada, o en un criterio de estado del encuentro; debe justificarse y probarse. En el tramo de aproximación, conservar exactamente la dependencia exponencial planteada.

**Nota importante:** esta puerta geométrica de fin de encuentro no debe redefinir `A` o `lambda` como meros umbrales de encendido. Lo principal sigue siendo una **intensidad continua** de esquiva. Es legítimo que, tras finalizar el encuentro o llegar al destino, la elusión se anule. No agregar parámetros de calibración ocultos.

También contemplar ausencia de vecino, `d=0`, velocidad nula o datos anómalos sin `NaN`/`Infinity`.

## 5. Cambios requeridos sobre la clase Java existente

La implementación anterior poseía:

- `horizon` (`T`), `clearance` (`D`) y `stepRad`;
- `predictedClearance(...)`, `conflict(...)`, `minClearance(...)`;
- búsqueda angular por `angleStep`, con `preferredSign(...)`;
- `self.theta`, `hasTemporalTarget`, `tempX`, `tempY`.

Modificar de la siguiente forma:

1. Reemplazar los dos **parámetros calibrables** `horizon` y `clearance` por `amplitude` (`A`) y `spatialRange` (`lambda`). Validar `Double.isFinite(value) && value > 0`.
2. Eliminar del **camino de decisión de la elusión** la detección binaria basada en `predictedClearance < clearance` y la búsqueda del primer ángulo discreto sin conflicto.
3. Eliminar la dependencia de `angleStepDeg` para calcular la maniobra. No conservarla como un tercer parámetro de la capa.
4. Calcular `offset = A * Math.exp(-distance / lambda)` y el target temporal según la sección 3.
5. Mantener la API consumida por el SFM (`hasTemporalTarget`, `tempX`, `tempY`). Si `theta` se usa en visualización/output, calcularlo como el ángulo **derivado** de la dirección efectiva del target, no como variable calibrable.
6. Preservar la lógica existente de llegada y la integración física SFM. Evitar cambiar la rapidez deseada o aplicar fuerzas adicionales.
7. Adaptar constructores, configuración JSON/TXT, validaciones, tests, nombres de parámetros en outputs, runners de experimentos y documentación. No dejar referencias antiguas a `T/D` como si fueran los parámetros vigentes.
8. Evitar cambiar innecesariamente el contrato público del resto del motor. Si hay scripts históricos que usan `T/D`, documentar de forma explícita que requieren migración; no hacer alias ambiguos que mezclen significados.

### Pseudocódigo orientativo

```java
void update(Agent self, Agent other, double arrivalTol) {
    double fx = self.finalX - self.x;
    double fy = self.finalY - self.y;
    double remaining = Math.hypot(fx, fy);
    if (remaining <= arrivalTol || other == null) {
        clear(self);
        return;
    }

    double ex = fx / remaining;
    double ey = fy / remaining;
    double dx = other.x - self.x;
    double dy = other.y - self.y;
    double d = Math.hypot(dx, dy);

    double a = amplitude * Math.exp(-d / spatialRange);
    // Aplicar acá el tratamiento definido de encuentro terminado
    // (suavizado geométrico/estado, sin parámetros calibrables adicionales).
    a *= encounterWeight(self, other, ex, ey);

    double nx = ey;   // derecha propia
    double ny = -ex;
    self.tempX = self.x + FIXED_LOOKAHEAD * ex + a * nx;
    self.tempY = self.y + FIXED_LOOKAHEAD * ey + a * ny;
    self.hasTemporalTarget = true;
    self.theta = Math.atan2(-a, FIXED_LOOKAHEAD); // signo para derecha propia
}
```

**El pseudocódigo NO está listo para copiar sin completar**: `encounterWeight(...)` se debe definir con una regla concreta, estable y testeable; `FIXED_LOOKAHEAD` debe justificarse y ser constante del diseño. No inventar que la transición está resuelta solo por incluir esa función. Asegurar que `theta` use la convención de signos coherente con el sistema de coordenadas.

## 6. Experimentos, observables y reproducibilidad

Conservar los requisitos del Problema 1 del PDF:

- Cada evento contiene **dos peatones**; inicializarlos con sus posiciones y velocidades experimentales iniciales.
- Target final de cada peatón = última posición de su trayectoria experimental.
- Módulo de velocidad deseada = rapidez media de la trayectoria experimental.
- Simular hasta el último instante experimental del evento.
- Para SFM mantener radio `r = 0.25 m` y demás parámetros del paper correspondiente.
- Calcular **ambas métricas ECM** definidas por la cátedra: comparación a iguales tiempos y comparación con punto simulado más cercano.
- Calibrar `A` y `lambda` **poblacionalmente** (un mismo par para todos los eventos) e **individualmente** (un par por evento), para cada métrica por separado.
- Realizar validación cruzada con separaciones aleatorias entrenamiento/validación y semillas reproducibles.
- Mostrar superficies/mapas de error `⟨E⟩(A, lambda)`, parámetros óptimos, distribuciones y comparaciones de errores.
- Animaciones superpuestas de trayectorias experimentales y simuladas y evolución temporal del observable primario.

El motor Java debe seguir exportando el estado temporal a TXT con suficiente información para recomputar los errores y las animaciones offline. No volver a simular cuando solo cambia un gráfico o el método de análisis. Sí es necesario volver a simular cuando cambian `A` o `lambda`.

## 7. Pruebas de aceptación mínimas

Agregar tests automatizados en Java y, cuando aplique, verificaciones en Python:

1. **Dos parámetros:** solo `A` y `lambda` son calibrables en la capa; ambos deben ser `>0` y finitos.
2. **Decaimiento espacial:** a `A,lambda` fijos, el offset se reduce monótonamente cuando aumenta `d`; para `d=0`, vale `A` antes de aplicar la lógica de encuentro.
3. **Efecto de A:** a igual distancia y `lambda`, duplicar `A` duplica el desplazamiento lateral de la fórmula base.
4. **Efecto de lambda:** para `d>0`, aumentar `lambda` incrementa el offset.
5. **Continuidad:** pequeñas variaciones de la distancia producen pequeñas variaciones del offset; comprobar especialmente el fin del encuentro para descartar saltos notorios en la dirección deseada.
6. **Dirección de avance:** el target proyecta una componente positiva hacia el destino; verificar que el módulo de velocidad deseada no cambie.
7. **Paso frontal:** dos agentes con direcciones opuestas toman sus respectivas derechas, con comportamiento estable y sin oscilaciones espurias.
8. **Después del cruce:** el efecto lateral decae hasta volver al target final.
9. **Casos límite:** llegada al destino, vecino ausente, posiciones coincidentes, distancias muy grandes, y valores inválidos de parámetros.
10. **Integración:** el motor compila con Maven, produce TXT válido y el postprocesamiento obtiene ambos ECM sin depender de ejecutar Java otra vez.
11. **Regresión:** comprobar que no hay fuerzas de elusión agregadas ni cambios en reglas de integración SFM.
12. **Validación física:** evaluar distancias mínimas efectivas entre agentes e inspeccionar si hay colisiones. La nueva fórmula no las evita por construcción; reportar los casos problemáticos en vez de afirmar seguridad garantizada.

## 8. Criterio de finalización

El cambio estará listo cuando la capa de elusión utilice `A` y `lambda` de manera documentada, el target temporal sea continuo frente a variaciones de proximidad, el encuentro termine sin maniobras erráticas, la integración SFM permanezca intacta, el sistema compile y los tests pasen. La calibración en Python debe poder barrer ambos parámetros y recuperar los ECM desde TXT.

**Prioridad en caso de duda:** `docs/TP5_Enunciado.pdf` > `AGENTS.md` > este documento para los requisitos obligatorios. Este archivo establece la propuesta de diseño para la elusión continua; decisiones no especificadas (como el valor de `L0` y la transición poscruce) deben quedar explícitas y justificadas, sin introducir un tercer parámetro ajustable.
