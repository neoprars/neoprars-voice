# Neoprars Voice – Arquitectura

Objetivo de diseño: **pasar de una instalación con claves propias a un producto comercial cambiando configuración, no reescribiendo.**
Las tres cosas que más cambiarán son (1) el proveedor de IA, (2) de dónde salen las credenciales, (3) cómo se muestra el botón en Android. Cada una tiene su costura.

## 1. Cambiar de IA sin dolor

```
DictationEngine ──usa──▶ Transcriber / Polisher   (interfaces, no saben de vendors)
                              ▲
                        EngineFactory             (único lugar que conoce las clases concretas)
                              ▲
                  EngineSetup (JSON, SIN claves)  ← "qué proveedor, qué modelo, qué URL"
                              ▲
                  KeyResolver (id → clave)        ← "dónde está la clave"
```

| Quiero… | Qué se toca |
|---|---|
| Usar otro **modelo** del mismo proveedor | 1 campo (`model`) en la configuración |
| Pasar a un proveedor **compatible con OpenAI** (más barato, o local como Ollama) | `baseUrl` + `model` + `id` de la clave. Código: 0 líneas |
| Pasar a un proveedor con **protocolo propio** | 1 clase nueva que implemente `Transcriber` o `Polisher` + 1 línea en `EngineFactory` + 1 valor en `Protocol` + su llamada de prueba de clave en `KeyCheck` (el compilador la exige) |
| Cambiar el **prompt** de pulido | `PolishPrompt.kt` (vale para todos los proveedores) |
| Sumar un proveedor **elegible en la app** | Una entrada en `ProviderCatalog.kt` (con su fuente); si habla un protocolo nuevo, además su clase y una línea en `EngineFactory` |
| Cambiar de IA **sin publicar una app nueva** | Entregar el `EngineSetup` desde un servidor propio (ya es JSON serializable) |

Detalles que ya están resueltos:
- `ProviderConfig` **no tiene secretos**: se puede guardar, versionar o descargar sin riesgo.
- El estilo de autenticación (`Bearer` o `x-api-key`) y las cabeceras extra son parte de la configuración.
- `baseUrl` va sin `/v1` (el motor agrega el path del protocolo).
- Si falla el pulido (red, saldo, respuesta rara), el dictado igual entrega el texto limpio por reglas.
- Los errores tienen tipo (`EngineException.Kind`), así la interfaz puede mostrar un mensaje claro sin depender del proveedor.

Lo que **no** es automático: cada proveedor tiene su calidad, sus límites y sus precios (también sus formatos de audio y tamaños máximos). Antes de migrar conviene probar el nuevo proveedor con audios reales (`LiveSmokeTest`) y comparar costo y calidad.

## 2. De claves en el dispositivo a un backend propio

El único archivo de la app que decide **qué proveedores** y **de dónde salen las claves** es `EngineProvider.kt`. La migración es:

| | Hoy (claves en el dispositivo) | Comercial |
|---|---|---|
| Setup | Selección del `ProviderCatalog` (por defecto `Presets.defaultSetup()`: OpenAI para transcribir y pulir) | `EngineSetup` descargado del backend, o apuntando todo a `https://api.<dominio-propio>` |
| Claves | Las de quien usa la app, cifradas en el teléfono | **Token de sesión** del usuario; las claves reales viven solo en el backend |
| Cobro/límites | No | En el backend (medición por usuario) |

Como el backend habla los **mismos protocolos** (por ejemplo `/v1/audio/transcriptions`), la app comercial es la misma app con otra configuración. El backend decide a qué proveedor reenvía; así cambiar de IA para todos los usuarios es un cambio en el servidor.

Preparación recomendada desde ya (barata ahora, cara después):
1. **`applicationId` definitivo:** `com.neoprars.voice` no se puede cambiar una vez publicada la app en Google Play. Hay que confirmarlo antes de la primera publicación.
2. **Dos sabores de compilación** (`direct` y `commercial`) que solo cambien la fuente de configuración y claves. Implementarlo cuando empiece la Fase 4.
3. **Textos en `strings.xml`** (ya es así) para poder traducir sin tocar código.
4. **No guardar** audio ni texto dictado; si hace falta historial, solo local y con opción de borrarlo.
5. **Telemetría** sin contenido: solo duración, latencia, tipo de error.
6. **Nunca** claves dentro del APK en la versión comercial.

## 3. Botón flotante (decidido: opción A, 2026-10-13)

> Estado: implementado (`NeoprarsVoiceAccessibilityService`, `FloatingButtonController`, `AccessibilityInserter`) y probado en un dispositivo real; checklist en `docs/SPIKE_A11Y.md`. Las opciones B y C siguen documentadas abajo porque la decisión de cómo publicar en Google Play se toma recién en la Fase 4.

Un botón que aparece al enfocar un campo de texto, en cualquier app, se puede lograr en Android de tres maneras:

| | A. Servicio de Accesibilidad + superposición | B. Teclado propio (IME) con botón de micrófono | C. Ambos, compartiendo el motor |
|---|---|---|---|
| Experiencia | La más parecida a Wispr Flow: botón flotante sobre cualquier teclado | Hay que cambiar de teclado para dictar | Lo mejor de los dos, doble trabajo de interfaz |
| Cómo detecta el campo | `AccessibilityService` (evento de foco en un nodo editable) | Lo recibe directamente el teclado | — |
| Cómo inserta el texto | Sobre el nodo enfocado (reemplazo de texto o pegado), más frágil entre apps | `commitText`, API oficial, estable | — |
| Permisos para el usuario | Activar el servicio en Ajustes (advertencia del sistema, es sensible) | Activar el teclado | Ambos |
| Google Play | **Riesgo alto**: la política de Play restringe y revisa el uso de Accesibilidad para funciones que no son de accesibilidad. Hay que verificar la política vigente antes de planear su publicación | Riesgo menor (aun así, los teclados se revisan con atención) | Publicar en Play solo B; A por otros canales |
| Riesgos técnicos | Hay que probar temprano que se pueda **grabar el micrófono** desde el servicio con la superposición visible en las versiones de Android objetivo; comportamiento de segundo plano varía por versión y fabricante | Bajos | Ambos |

**Diseño para que la elección no sea irreversible:** la lógica se divide en tres piezas intercambiables en `android-app`:

```
Disparador        →  Grabador          →  Motor             →  Insertador
(botón flotante     (AudioRecorder)      (DictationEngine,     (TextInserter)
 o botón del                              en :engine)           ├─ AccessibilityInserter
 teclado)                                                       └─ ImeInserter
```

El motor y el grabador no cambian. Pasar de A a B (o tener ambos) es sumar un disparador y un insertador, no rehacer la app. Esa separación se crea en la Fase 2.

**Decisión:** se empezó por A (fuera de la tienda no hay revisión de Play), validada con un *spike* de micrófono + superposición + inserción en apps comunes (mensajería, notas, navegador). Cómo publicar (B en Play, A por fuera, o ambas) se decide en la Fase 4, con la política de Play a la vista.

## 4. Estado del código

- `engine/`: pipeline, proveedores intercambiables, tests con servidores simulados. **Compila y pasa los 132 tests** de `./gradlew :engine:jvmTest` (2026-10-14). Falta la prueba real contra las APIs (`LiveSmokeTest` con clave y un WAV).
- `android-app/`: botón flotante (Accesibilidad) como forma principal de dictar y teclado propio (IME) opcional, con el mismo motor. Pantalla de ajustes con el catálogo de proveedores.
- La lógica de la app que no depende de Android (`TextSplice`: cómo insertar en el campo; `ButtonPlacement`: dónde va el botón) tiene tests unitarios, igual que las reglas de la pantalla de ajustes (`SettingsForm`): `./gradlew :android-app:testDebugUnitTest` (26 tests).
- El audio se sube comprimido: `AacEncoder` (en `android-app`, usa el codificador del sistema) implementa `AudioEncoder` del motor. Sin tests automáticos: depende de Android; se verifica en un dispositivo con `encode_ms` y `sent_kb` del registro.
- Medición de latencia: `adb logcat -s NeoprarsVoice` muestra solo duraciones (`audio_ms`, `encode_ms`, `sent_kb`, `stt_ms`, `upload_ms`, `wait_ms`, `polish_ms`, `insert_ms`, `stop_wait_ms`) y datos del campo sin contenido. `stop_wait_ms` es la espera desde el toque de parar hasta el texto insertado; con tramos (`chunked=true`) hay además una línea `chunk=N/M` por tramo con su estado, duraciones, reintentos y si hubo reenvío (`hedged`). `noise_rms` y `silence_thr` son el ruido medido al empezar y el umbral de pausa usado (niveles, no audio).
- Dictado por tramos: `ChunkedDictationSession` (en `engine/`) recibe el audio con `push` mientras se graba, corta en las pausas y reusa los mismos pasos que `dictate` (empaquetar, transcribir, reglas, pulir). Los parámetros viven en `DictationConfig` (`chunkSilenceMs`, `minChunkMs`, `maxChunkMs`, `noiseCalibrationMs`, `silenceNoiseFactor`, `silenceThresholdMin`/`Max`, `maxParallelChunks`, `chunkRetries`, `hedgeAfterMs`, `polishContextChars`). `Polisher.polish` acepta `previousText` como contexto de solo lectura.
- El wrapper de Gradle (8.7) está en el repo: `./gradlew :engine:jvmTest` funciona sin instalar Gradle.
