# Neoprars Voice – Roadmap

Dictado por voz con botón flotante sobre cualquier app. Primero Android, después iOS, con camino a producto comercial. El **motor** vive separado de las apps para reutilizarlo.

> Los tiempos son orientativos (trabajando algunas horas por día). Los precios y políticas corresponden a octubre de 2026: hay que verificarlos antes de decidir.

---

## Decisiones tomadas

| # | Decisión | Fecha |
|---|---|---|
| 1 | Nombre del producto: **Neoprars Voice** (paquete `com.neoprars.voice`) | 2026-10-13 |
| 2 | Experiencia objetivo: **botón flotante** que aparece al enfocar un campo de texto (como Wispr Flow) | 2026-10-13 |
| 3 | Pensado para comercializar: cambiar de proveedor de IA o pasar a backend propio debe ser **configuración, no reescritura** (ver `ARCHITECTURE.md`) | 2026-10-13 |
| 4 | Botón flotante con **opción A: servicio de Accesibilidad + superposición**. El teclado propio (B) queda como camino alternativo, sobre todo para Google Play | 2026-10-13 |
| 5 | Wrapper de **Gradle 8.7**: es el mínimo que pide Android Gradle Plugin 8.5.2 y está dentro de lo que soporta Kotlin 2.0.20 (Gradle 9.x no es compatible con esas versiones) | 2026-10-13 |
| 6 | Botón flotante de **un toque**: tocar empieza a grabar, tocar de nuevo para y dicta. La grabación se **corta sola tras 5 s de silencio** (o al tope de 120 s) y dicta lo grabado. El teclado propio sigue con "mantener para hablar" | 2026-10-13 |
| 7 | Latencia: los dictados de **menos de 6 palabras no se pulen** con IA (`minWordsToPolish`, se entrega el texto limpio por reglas); el motor se reutiliza entre dictados y las conexiones se **precalientan al empezar a grabar**. Medido antes: transcripción ~1,5 s sin importar el largo, pulido ~1 s fijo | 2026-10-13 |
| 8 | Inserción: si el campo no informa selección (WhatsApp devuelve su hint "Mensaje" como texto), el dictado se **pega en el cursor** en vez de reescribir el campo | 2026-10-13 |
| 9 | Botón flotante **arrastrable**: se pega al borde izquierdo o derecho y recuerda la posición por orientación; nunca queda detrás del teclado | 2026-10-13 |
| 10 | ~~La app usa **solo OpenAI** (una clave): transcripción con `gpt-4o-mini-transcribe` y pulido con `gpt-6-luna` sin razonamiento (`reasoning_effort: none`). Se quitó Anthropic de la app; `ClaudePolisher` queda en el motor como opción de configuración~~ (revertida en parte por la 15) | 2026-10-13 |
| 11 | El audio se sube **comprimido en AAC (.m4a, 48 kbps)**, codificado en memoria; si falla o en Android anterior al 11, se sube WAV. Motivo: las demoras de 10–25 s medidas eran trabas de subida. Medido después (audios de 36–49 s): comprimir 0,2–0,3 s, subir <0,3 s, total 5,5–6,8 s; el resto es espera de OpenAI (transcripción ~2,7 s, pulido ~2,4–3,5 s) | 2026-10-14 |
| 12 | **Dictado por tramos** (activado por defecto, interruptor en la pantalla principal): mientras se graba, una pausa de 0,7 s con al menos 3 s de audio cierra un tramo (o se fuerza a los 25 s, en el momento más silencioso) que se transcribe y pule en segundo plano, con hasta 2 tramos a la vez y un reintento ante errores de red o del servidor. El pulido recibe el final del tramo anterior como contexto. Al parar solo se espera el último tramo; los textos se unen en orden y se insertan una vez. Un solo tramo = mismo comportamiento que antes. Motivo: con audios de 36–49 s se esperaban ~5,5–6,8 s después de parar. Medido con tramos: ~2,4–3,1 s | 2026-10-14 |
| 13 | Tramos, ajuste tras medir: (a) corte forzado a **12 s** (antes 25 s), porque casi ninguna pausa natural cortaba; (b) **umbral de pausa adaptativo**: el ruido de fondo se mide en los primeros 300 ms (bloque más silencioso) y el umbral es ruido × 2, entre 0,01 y 0,03 RMS; la voz de cada tramo se sigue contando con el umbral fijo para no descartar voz baja; (c) **reenvío por lentitud**: si una transcripción tarda más de 4 s se manda el mismo audio otra vez y gana la primera respuesta (máximo un reenvío por tramo; `hedgeAfterMs`, null lo apaga). Motivo: una respuesta de OpenAI de 12,7 s llevó una espera a 11,5 s | 2026-10-14 |
| 14 | Pantalla de ajustes: los **interruptores y selectores** (pulido, tramos, idioma, estilo, proveedor) se aplican y guardan **al instante** con un Snackbar breve. Los **campos que se escriben** (claves, modelos, vocabulario) usan **"Guardar cambios"**: neutro/deshabilitado sin cambios, color de acento con cambios pendientes y diálogo al salir sin guardar. En ambos casos el motor se rearma en `EngineProvider` | 2026-10-14 |
| 15 | **Catálogo de proveedores** (`ProviderCatalog`, en el motor): la persona elige transcripción y pulido; una clave por empresa; modelo opcional por proveedor (vacío = el de fábrica). Revierte en parte la decisión 10: **Anthropic Claude vuelve como opción de pulido** (`claude-haiku-5-5`, sin razonamiento). Pulido: OpenAI `gpt-6-luna` con `reasoning_effort: none` (USD 0,10 / 0,50 por millón de tokens de entrada/salida, verificado en developers.openai.com), Claude, **Gemini** `gemini-3.5-flash-lite` por su ruta compatible con OpenAI (`/v1beta/openai/chat/completions`; en Gemini 3.x el razonamiento no se apaga, Flash-Lite ya usa el mínimo; USD 0,30 / 2,50) y **xAI Grok** `grok-4.20-0309-non-reasoning` (`/v1/chat/completions`, que xAI marca como "deprecated / legacy" sin fecha de baja; USD 1,25 / 2,50). Transcripción: solo OpenAI; **Deepgram** (`nova-3`, `/v1/listen`, `Authorization: Token`) y **Gemini** (`gemini-3.5-transcribe`) quedan declarados como "no implementado" y ocultos. **Estilo de pulido** (Neutro, Formal, Informal, Conciso) con guardado instantáneo. Fuentes en `ProviderCatalog.kt` | 2026-10-14 |
| 16 | **Rediseño de la pantalla de ajustes**: monocromática como el teclado (grafito / blanco, modo oscuro), título grande sin barra, tarjetas redondeadas por sección (Estado, Dictado, Pulido, Claves y modelos, Activación, Prueba), selectores desplegables y solo los botones de activación que faltan. Sin emojis | 2026-10-14 |
| 17 | **"Probar clave"** en cada clave pedida: lista los modelos del proveedor (`GET /v1/models`; Gemini `/v1beta/openai/models`; Anthropic con `limit=1`), una llamada sin texto ni audio y sin costo. Prueba lo escrito en el campo o, si está vacío, la guardada; corta a los 10 s. Resultado como `EngineException.Kind` (401/403 → clave inválida, 402/429 → sin saldo o límite, red → sin conexión; en Gemini, 400 con `API_KEY_INVALID` también se toma como clave inválida, **sin comprobar en vivo**), sin el cuerpo de la respuesta porque algunos proveedores repiten la clave. **No detecta falta de saldo** (el resultado válido lo dice: "Clave válida (no verifica saldo)"): eso pide una llamada real al modelo, con costo, y se decidió no hacerla. Lógica en `KeyCheck` (motor), por protocolo. El catálogo marca cada proveedor como probado (solo OpenAI, de punta a punta), experimental o próximamente | 2026-10-14 |
| 18 | **Ayuda para conseguir la clave**: botón "?" junto a los selectores de transcripción y de pulido; abre una hoja inferior con los pasos del proveedor elegido, el botón "Abrir página de claves" (navegador del sistema) y avisos (clave personal, uso cobrado en la cuenta del proveedor, revisar la clave completa y sin espacios). El catálogo guarda solo el identificador, la URL de la página de claves y la fuente (`ProviderCatalog.keyHelp`); los textos están en `strings.xml`. Pasos tomados de la documentación oficial de OpenAI, Anthropic, Google, xAI y Deepgram (2026-10-14); en OpenAI la guía solo dice "crear una clave en el panel", así que los pasos son mínimos | 2026-10-14 |
| 19 | **Transcripción con Deepgram y Gemini** (experimentales; todos los proveedores visibles en los selectores, con su estado del catálogo). **Deepgram** `nova-3`: `POST /v1/listen`, `Authorization: Token`, audio crudo con su Content-Type (MP4, M4A, AAC y WAV figuran entre los formatos; hasta 2 GB), `language=es` (sin idioma: `multi`, porque Deepgram asume inglés), `smart_format=true`, vocabulario como `keyterm` repetido, uno por término (solo Nova-3 y Flux; hasta 50). **Gemini** `gemini-3.5-transcribe`, modelo de transcripción dedicado: `POST /v1beta/interactions`, `x-goog-api-key`, audio en base64 (pedido de hasta 20 MB; el M4A de la app se declara `audio/m4a` porque Gemini no lista `audio/mp4`), idioma en `language_codes` y vocabulario en `custom_vocabulary` (el mecanismo propio del modelo, en vez de una instrucción en texto; modo literal, el predeterminado), **`store: false`** (si no, Google guarda el pedido 1 a 55 días). Precio estimado por Google: USD 0,003/min. Defensa contra respuestas que no son transcripción: se quitan presentaciones ("Aquí está la transcripción:"), comillas y bloques de código; un rechazo corto que habla del audio, una respuesta fallida o sin salida del modelo es error, nunca texto. **Probar clave**: Deepgram `GET /v1/projects`; Gemini `GET /v1beta/models?pageSize=1`. Los mensajes de error ya no incluyen el cuerpo de la respuesta (podía repetir la clave o el texto). Fuentes: developers.deepgram.com (pre-recorded-audio, supported-audio-formats, keyterm, models-languages-overview, language, reference/manage/projects/list); ai.google.dev (gemini-api/docs/transcribe, audio, interactions, models, models/gemini-3.5-transcribe, pricing; api/models, api/interactions-api). **Sin comprobar en vivo**: que Deepgram acepte el M4A tal como lo arma la app (su lista dice MP4, M4A y AAC, sin detallar AAC dentro de MP4); que `/v1/projects` no tenga costo ni pida permisos especiales (la documentación no lo dice; no procesa audio); cómo informa Gemini un bloqueo por seguridad en esta API (no está documentado: se trata como error cualquier respuesta sin salida del modelo); el 400 `API_KEY_INVALID`; que `models.list` acepte la clave en la cabecera `x-goog-api-key` (su ejemplo la pasa en la URL); y si `custom_vocabulary` mejora el resultado (hay reportes en el foro de que en modo "smart" no hace efecto) | 2026-10-14 |

Las pruebas en dispositivo y sus resultados se registran en `docs/SPIKE_A11Y.md`.

---

## 1. Cómo se ejecuta

```
[Tocás el botón]  →  AudioRecord (16 kHz, mono, PCM16)
        │ tocás de nuevo (o 5 s de silencio)
        ▼
   ENGINE.dictate(pcm)        (con tramos: ChunkedDictationSession hace 2–5 por cada pausa mientras grabás)
     1. Puerta de calidad: descarta audio < 0,4 s o silencioso (evita llamadas inútiles)
     2. Empaqueta el audio (AAC comprimido en Android; WAV por defecto)
     3. Transcribe  → OpenAI (gpt-4o-mini-transcribe o gpt-transcribe)
     4. Limpieza por reglas (muletillas, mayúsculas, "nueva línea")   ← sin red
     5. Pulido opcional → OpenAI (gpt-6-luna), solo si el dictado tiene 6 palabras o más
        └ si falla o responde algo raro, se usa el texto del paso 4
        ▼
[Teclado Android]  commitText(texto)  →  aparece en la app que estés usando
```

Latencia esperada: 1–3 s por frase (se mide en un dispositivo real con el registro `NeoprarsVoice`).
Por defecto todo pasa por OpenAI con una sola clave. La persona puede elegir otro servicio de pulido (Claude, Gemini, Grok) en ajustes; los proveedores salen de `ProviderCatalog`.

## 2. Dónde van las clases

```
neoprars-voice/
├── CLAUDE.md                 ← instrucciones permanentes para Claude Code
├── docs/                     ← ROADMAP.md, DECISIONS.md (bitácora de decisiones)
├── engine/                   ← Kotlin Multiplatform. NO sabe nada de Android ni de pantallas.
│   └── src/
│       ├── commonMain/       ← 95 % del código, compartido
│       │   ├── Audio.kt          Pcm16 (nivel/duración), WavEncoder
│       │   ├── AudioEncoder.kt   costura para comprimir el audio antes de subirlo (WAV por defecto)
│       │   ├── SilenceDetector.kt  silencio sostenido, para cortar sola la grabación
│       │   ├── ChunkedDictationSession.kt  dictado por tramos: transcribe en cada pausa mientras se graba
│       │   ├── Providers.kt      interfaces Transcriber y Polisher  ← punto de extensión
│       │   ├── ProviderConfig.kt Protocol, ProviderConfig, EngineSetup (JSON, sin claves), Presets
│       │   ├── ProviderCatalog.kt  proveedores elegibles (implementados o no), con fuentes
│       │   ├── EngineFactory.kt  único lugar que mapea protocolo → clase
│       │   ├── OpenAiTranscriber.kt / ClaudePolisher.kt / OpenAiChatPolisher.kt  ← implementaciones
│       │   ├── PolishPrompt.kt   prompt de pulido independiente del proveedor y estilos (PolishStyle)
│       │   ├── RuleCleaner.kt    limpieza sin red
│       │   ├── DictationEngine.kt  el pipeline completo
│       │   └── Errors.kt         EngineException con tipos de error
│       ├── androidMain/ jvmMain/ iosMain/   ← solo el cliente HTTP de cada plataforma
│       └── commonTest/       ← tests con servidores simulados (sin red, sin claves)
├── android-app/              ← UI y pegamento: botón flotante / teclado, micrófono, ajustes, claves
├── ios-app/                  ← (fase 5) teclado + app contenedora en SwiftUI
└── backend/                  ← (fase 4) proxy para la versión comercial
```

**Regla de oro:** si una clase necesita `Context`, `View` o `AudioRecord`, va en `android-app`. Si es lógica pura de dictado, va en `engine`. Así el motor se reutiliza en iOS, escritorio u otra app.

Para sumar otro proveedor (por ejemplo, un Whisper local para modo offline) solo se implementa `Transcriber`; el resto no cambia.

## 3. ¿Es posible hacerlo comercial? Sí, pero cambia 4 cosas

| Tema | Claves en el dispositivo (hoy) | Comercial |
|---|---|---|
| **Claves de API** | En el teléfono, cifradas | **Nunca dentro de la app**: se extraen. Backend propio que las guarda y hace de proxy (o modo "traé tu propia clave") |
| **Cuentas y uso** | No hay | Login, límites por usuario, medición de minutos |
| **Cobro** | No hay | Google Play Billing / App Store IAP (comisión de las tiendas); Stripe en web/escritorio |
| **Privacidad** | El audio es de quien instala la app y usa sus propias claves | Política de privacidad, consentimiento explícito de que el audio va a terceros, no guardar audio, declaración de seguridad de datos en Play. Ley 25.326 (Argentina) y GDPR si hay usuarios de la UE |

Otros puntos:

- **Costo por usuario (estimado):** con `gpt-4o-mini-transcribe` (~US$0,003/min), 20 min/día son ~US$1,8/mes de transcripción más centavos del pulido. Un usuario intensivo de 60 min/día ronda US$5,4/mes. Un precio de US$8–12/mes con tope de uso justo deja margen.
- **Los teclados son sensibles para las tiendas y los usuarios:** un teclado ve lo que escribís. Transparencia total o no te instalan.
- **Nombre:** evitar "Whisper Flow", "Wispr" y "Whisper" (marca de un competidor y nombre de un modelo de OpenAI); verificar marca registrada antes de invertir en el nombre.
- **Términos de los proveedores:** revisar los términos comerciales y de retención de datos de cada proveedor del catálogo (OpenAI, Anthropic, Google, xAI) antes de lanzar.
- **Diferenciador posible:** modo 100 % local (el prototipo offline con sherpa-onnx) como plan "privado", vocabulario propio, tonos por app y español rioplatense bien tratado. Ya existen competidores fuertes, así que conviene elegir un nicho.

## 4. Fases (cada una cierra con decisiones antes de avanzar)

### Fase 0 – Entorno (1 día)
- Android Studio (SDK + emulador), JDK 17 o superior y Git (ver sección 5).
- Un teléfono Android real con depuración USB (el micrófono del emulador es poco fiable).
- Claves de API dedicadas al proyecto y **con límite de gasto mensual** bajo.
- **Decisiones:** nombre del proyecto, repositorio, estructura monorepo.
- **Listo cuando:** el proyecto compila y una app mínima corre en un dispositivo.

### Fase 1 – Motor validado por consola (2–3 días)
- Construir `engine` en JVM: WAV, `OpenAiTranscriber`, `RuleCleaner`, `ClaudePolisher`, `DictationEngine` y tests con respuestas simuladas.
- Prueba real: un WAV de prueba → texto, midiendo latencia y costo (`LiveSmokeTest`).
- **Decisiones:** modelo de transcripción (`gpt-4o-mini-transcribe` barato vs `gpt-transcribe` recomendado hoy por OpenAI), idioma fijo o automático, texto del prompt de pulido, y si el motor es Kotlin Multiplatform (sirve para iOS) o solo Kotlin.
- **Listo cuando:** los tests pasan y una frase real queda bien transcripta y pulida.

### Fase 2 – Android MVP (3–5 días)
- Teclado (IME) con botón de hablar, grabación, llamada al motor, inserción de texto, pantalla de ajustes con claves cifradas.
- **Decisiones:** mantener pulsado vs tocar para empezar/terminar; teclado completo con letras vs solo botón de dictado + teclado habitual.
- **Listo cuando:** se puede dictar en apps de mensajería y notas, y aguanta un día entero de uso.

### Fase 3 – Experiencia (1–2 semanas)
- Indicador de nivel de audio, historial local, deshacer, vocabulario propio de cada usuario, modos por app (formal/casual), manejo claro de errores y falta de conexión.
- **Decisiones:** priorizar lo que más molesta en el uso diario real.

### Fase 4 – Camino comercial (3–6 semanas)
- Backend proxy (por ejemplo, funciones serverless) con autenticación, límites y medición; la app deja de llevar claves.
- Cobro, política de privacidad, términos, declaración de datos, beta cerrada en Google Play.
- **Decisiones:** modelo de negocio (suscripción vs créditos vs "traé tu clave"), precio, dónde alojar el backend, qué telemetría (sin audio ni texto).
- **Listo cuando:** 10–20 personas de confianza lo usan en beta y el costo por usuario coincide con la estimación.

### Fase 5 – iOS (6–10 semanas)
- Requiere una Mac con Xcode y la cuenta de Apple Developer (de pago anual).
- El motor se compila como framework. La UI se hace en SwiftUI.
- Restricción conocida: las extensiones de teclado de iOS tienen poca memoria y piden "Acceso completo" para usar red, y grabar desde el teclado es más limitado. Probar temprano si se puede grabar dentro del teclado o si hay que pasar por la app contenedora.
- **Decisiones:** teclado propio vs flujo "abrir la app, dictar y volver".

### Fase 6 – Ampliaciones
Escritorio (atajo global + pegado), modo local como plan privado, más idiomas.

## 5. Desarrollo

**Requisitos:** JDK 17 o superior, Android SDK (por ejemplo, el de Android Studio) y un dispositivo Android real con depuración USB: el micrófono del emulador es poco fiable. El wrapper de Gradle (8.7) está en el repositorio.

**Comandos:**
- Tests del motor: `./gradlew :engine:jvmTest`
- Tests de la app (lógica sin Android): `./gradlew :android-app:testDebugUnitTest`
- Compilar e instalar en el dispositivo: `./gradlew :android-app:installDebug`
- Prueba real con APIs (opcional, nunca en CI): `OPENAI_API_KEY=... NEOPRARS_WAV=/ruta/audio.wav ./gradlew :engine:jvmTest --tests '*LiveSmokeTest*'`
- Medir latencia: `adb logcat -s NeoprarsVoice` (solo duraciones y conteos, ver `ARCHITECTURE.md`)

**Reglas del proyecto:**
1. `engine/` no depende de Android ni de interfaz; la lógica de dictado vive solo ahí. Sin Regex en código común (portabilidad a iOS).
2. Cada pieza nueva lleva tests con servidores simulados; los tests no usan la red.
3. Nunca escribir claves en código, tests, registros ni commits; nunca registrar ni guardar audio o texto dictado.
4. Textos de interfaz en `strings.xml`, en español rioplatense.
5. Cada decisión de diseño se anota en la tabla "Decisiones tomadas" de este documento.

## 6. Decisiones abiertas (para ir resolviendo)

1. Nombre del producto.
2. Motor en Kotlin Multiplatform (comparte con iOS) vs solo Kotlin.
3. Modelo de transcripción por defecto (costo vs calidad) y si el pulido va activado de entrada.
4. Interacción: mantener pulsado vs tocar para alternar; teclado completo vs botón de dictado.
5. Comercial: ¿suscripción, créditos o "traé tu clave"? ¿Hay modo local gratuito?
6. Backend: dónde alojarlo y con qué autenticación.
7. Idiomas objetivo y mercados (afecta legal y precios).
8. ¿Cuándo empezar iOS? Condicionarlo a que Android tenga uso real sostenido.
