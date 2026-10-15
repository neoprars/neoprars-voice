# Neoprars Voice

Dictado por voz para Android. Un botón flotante aparece sobre el teclado en cualquier app: lo tocás, hablás,
lo tocás de nuevo y el texto se escribe en el campo donde estabas. La voz se transcribe con un servicio de IA
y, si querés, el texto se pule (puntuación, mayúsculas, muletillas) con otro modelo de IA antes de insertarse.

- Funciona en cualquier campo de texto: mensajería, correo, notas, navegador.
- **Dictado por tramos**: mientras hablás, cada pausa se transcribe en segundo plano, así la espera al terminar es corta.
- **Pulido opcional** con estilos: Neutro, Formal, Informal o Conciso.
- Incluye además un teclado propio, opcional, con botón de dictado.
- Elegís qué servicio transcribe y cuál pule, cada uno con su propia clave.

## Estado del proyecto y licencia

Proyecto en desarrollo activo, probado en un teléfono real con Android 16.

El código está disponible bajo la licencia **PolyForm Noncommercial 1.0.0** (ver [LICENSE](LICENSE)).
**No es software de código abierto**: podés usarlo, estudiarlo y modificarlo con fines no comerciales,
pero **no se permite el uso comercial**. El nombre y el logo tienen reglas aparte (ver [Marca](#marca)).

## Requisitos y cómo compilar

- [Android Studio](https://developer.android.com/studio) (incluye el SDK de Android).
- JDK 17 (el que trae Android Studio sirve).
- Un teléfono con Android 8.0 o superior y la **depuración USB** activada
  (Ajustes → Acerca del teléfono → tocar 7 veces "Número de compilación"; después Opciones de desarrollador → Depuración USB).

Con el teléfono conectado por USB:

```sh
./gradlew :android-app:installDebug          # en Windows: gradlew.bat :android-app:installDebug
```

Tests (no usan internet: los servicios se simulan):

```sh
./gradlew :engine:jvmTest                    # motor de dictado
./gradlew :android-app:testDebugUnitTest     # lógica de la app
```

Al abrir la app, la pantalla de ajustes indica qué falta: permiso de micrófono, clave y activación del botón flotante.

### Código fuente

El código está en **https://github.com/neoprars/neoprars-voice**.

### Estructura

- `engine/`: el motor de dictado (grabación por tramos, transcripción, reglas y pulido). Kotlin Multiplatform,
  sin dependencias de Android, pensado para reutilizarse en iOS.
- `android-app/`: la app (botón flotante, teclado y pantalla de ajustes).
- `docs/`: arquitectura (`ARCHITECTURE.md`) y hoja de ruta con las decisiones tomadas (`ROADMAP.md`).

## Proveedores

| Proveedor | Transcribir | Pulir | Estado |
|---|---|---|---|
| OpenAI | Sí (`gpt-4o-mini-transcribe`) | Sí (`gpt-6-luna`) | **Probado** |
| Deepgram | Sí (`nova-3`) | — | Experimental |
| Google Gemini | Sí (`gemini-3.5-transcribe`) | Sí (`gemini-3.5-flash-lite`) | Experimental |
| Anthropic Claude | — | Sí (`claude-haiku-5-5`) | Experimental |
| xAI Grok | — | Sí (`grok-4.20-0309-non-reasoning`) | Experimental |

Solo **OpenAI está probado de punta a punta** en un teléfono, dictando de verdad. Los demás están implementados
y pasan los tests con servidores simulados, pero son **experimentales**. Si alguno falla, abrí un [issue](https://github.com/neoprars/neoprars-voice/issues) con
el proveedor, el modelo y qué pasó (sin incluir tu clave ni el texto dictado). En los selectores de la app,
los experimentales llevan esa etiqueta; un proveedor anunciado pero todavía sin implementar aparece como
"Próximamente" y no se puede elegir.

Los modelos y precios cambian seguido. Cada proveedor del catálogo
(`engine/src/commonMain/kotlin/com/neoprars/voice/engine/ProviderCatalog.kt`) cita la documentación oficial de
donde salen sus datos, y en la app se puede escribir otro modelo para cada servicio.

## Claves

La app no incluye ninguna clave ni un servicio propio en el medio: **cada persona crea su cuenta y su clave**
con el proveedor que elija, y **el uso se cobra en su cuenta**. Para dictar con los valores de fábrica alcanza
con una clave de OpenAI, que transcribe y pule.

- Junto a los selectores de servicio de transcripción y de pulido, el botón **"?"** explica paso a paso cómo
  conseguir la clave del proveedor elegido y abre su página de claves.
- **"Probar clave"** comprueba que la clave funcione con una consulta sin costo (listar los modelos del proveedor).
  Distingue clave inválida, sin conexión y, cuando el proveedor lo informa, límite alcanzado. No puede
  confirmar que haya saldo: eso solo se sabe al usar el modelo.
- La clave se guarda **cifrada en el teléfono** (Android Keystore) y nunca forma parte del código ni del repositorio.

## Privacidad

- La app **no guarda audio ni texto dictado**. El audio se manda solo al proveedor de transcripción que elegiste
  y, si activaste el pulido, el texto transcripto se manda al proveedor de pulido que elegiste.
- El registro interno de la app (logcat) anota solo duraciones y conteos, para medir la latencia. Nunca anota texto, audio ni claves.
- No hay telemetría, analíticas ni servidores propios.
- Con Gemini, la app pide que el proveedor no guarde el pedido (`store: false`); si no, Google lo conservaría
  para recuperarlo después.
- Las claves se guardan cifradas en el teléfono y solo salen de él hacia el proveedor correspondiente.
- Lo que haga cada proveedor con el audio o el texto depende de sus propias condiciones de uso.

## Servicio de accesibilidad

El botón flotante funciona como un **servicio de accesibilidad** de Android. Se usa para:

- detectar cuándo hay un campo de texto enfocado, para mostrar el botón solo en ese momento;
- saber dónde está el teclado, para ubicar el botón justo arriba;
- insertar el texto dictado en ese campo, en la posición del cursor.

Lo que **no** hace:

- No actúa en campos de contraseña: ahí el botón no aparece.
- No guarda ni envía el contenido de la pantalla. Para insertar en el cursor, lee el texto del campo enfocado
  en el momento de insertar, y no lo guarda.
- No toca nada si no tocaste el botón.

**Cómo activarlo:** en la app, "Activar el botón flotante" lleva a Ajustes → Accesibilidad → Neoprars Voice.
Android muestra una advertencia general ("puede ver y controlar la pantalla") porque es el permiso
que piden todos estos servicios. La app lo usa solo para lo que se describe arriba.

**Si el interruptor aparece bloqueado** ("Ajuste restringido"): desde Android 13, el sistema bloquea este
permiso en apps instaladas fuera de una tienda. Para habilitarlo: Ajustes → Aplicaciones → Neoprars Voice →
menú ⋮ (arriba a la derecha) → **Permitir ajustes restringidos**, y después volvé a activarlo en Accesibilidad.
En algunos teléfonos, la opción del menú ⋮ aparece recién después de haber intentado activar el servicio una vez.

## Limitaciones conocidas

- **Algunas apps no aceptan la inserción directa.** En esos casos, el texto se pega usando el portapapeles:
  pasa por ahí un instante y después se vuelve a dejar lo que había antes. El dictado se marca como contenido
  sensible (Android 13 y posteriores no muestran su vista previa), pero un teclado con historial de
  portapapeles podría guardarlo.
- **Android puede bloquear el micrófono**: si está desactivado en los ajustes rápidos (privacidad) o si otra app
  lo está usando, por ejemplo durante una llamada. La app avisa y no graba.
- **No está pensada para publicarse en Google Play** tal como está: Google Play exige requisitos específicos para
  usar el servicio de accesibilidad, y habría que revisarlos antes de publicarla ahí.

## Contribuciones

- Las fallas y sugerencias se reportan en [Issues](https://github.com/neoprars/neoprars-voice/issues).
  No incluyas claves, audio ni texto dictado en los reportes.
- Antes de un cambio grande, abrí un issue para conversarlo.
- Los cambios tienen que pasar los tests del motor y de la app (ver [Requisitos y cómo compilar](#requisitos-y-cómo-compilar)).

## Marca

El nombre "Neoprars Voice" y el logo **no están cubiertos por la licencia** del código. Si distribuís una
versión modificada, usá otro nombre y otro ícono. Ver [NOTICE](NOTICE).

## Créditos

© 2026 NeoPrars.
