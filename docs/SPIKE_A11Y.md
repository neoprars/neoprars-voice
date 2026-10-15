# Prueba de concepto: botón flotante con Accesibilidad

Objetivo: confirmar en tu teléfono real, en 1–2 días, que el botón aparece, que se puede grabar el micrófono y que el texto se inserta en las apps que usás. **Nada de esto fue probado todavía.**

## Cómo funciona (qué se probó "en papel")

1. El servicio escucha eventos de foco y de ventanas.
2. Si hay un campo **editable, que no sea de contraseña**, enfocado **y** una ventana de teclado visible, muestra el botón arriba del teclado (esquina derecha).
3. Tocás el botón: graba. Tocás de nuevo (o pasan 5 s de silencio): motor → texto → se escribe en el campo enfocado.
4. El botón no toma el foco, así que el teclado y el campo siguen como estaban.

## Preparación

1. Compilar e instalar (`./gradlew :android-app:installDebug`) con el teléfono en modo depuración USB.
2. En la app: guardar la clave de OpenAI, dar permiso de micrófono.
3. "Activar el botón flotante": leés el aviso, vas a Ajustes → Accesibilidad → Neoprars Voice → activar.
   - Si el sistema ve la app como instalada fuera de la tienda, Android puede bloquear el interruptor. En ese caso: Ajustes → Aplicaciones → Neoprars Voice → menú de tres puntos → "Permitir ajustes restringidos", y repetir.

## Checklist (anotá el resultado de cada una)

Probá con la versión de Android y el modelo de tu teléfono, y anotalos acá.

| # | Prueba | Esperado | Resultado |
|---|---|---|---|
| 1 | Tocar un campo en la app de prueba de Neoprars Voice | Aparece el botón sobre el teclado | |
| 2 | Cerrar el teclado | El botón desaparece | |
| 3 | Tocar un campo de contraseña | **No** aparece | |
| 4 | Tocar, decir una frase, tocar de nuevo | Disco blanco con anillos al grabar, arco giratorio al procesar, texto en el campo | |
| 4b | Tocar, decir una frase y quedarse callado | A los 5 s de silencio corta solo y escribe el texto | |
| 4c | Tocar, cerrar el teclado mientras graba | Sigue grabando y el botón sigue visible | |
| 4d | Tocar justo al lado del botón (zona del halo) y la fila superior del teclado | Anotar si el halo tapa toques de la app o del teclado | |
| 4e | Arrastrar el botón y soltarlo; cerrar y abrir el teclado; girar la pantalla | Se pega al borde más cercano, recuerda la posición por orientación y nunca queda detrás del teclado | OK (2026-10-14) |
| 4f | Tocar el botón sin mover el dedo después de haberlo arrastrado | Sigue funcionando como un toque (no arrastra) | OK (2026-10-14) |
| 4g | WhatsApp con el campo vacío | El texto no empieza con "Mensaje"; el portapapeles conserva lo que tenía | OK (2026-10-14) |
| 5 | Dictar con texto ya escrito y el cursor en el medio | El texto entra en el cursor, sin pisar lo existente | |
| 6 | Seleccionar texto y dictar | Reemplaza la selección | |
| 7 | WhatsApp (chat) | Aparece e inserta | |
| 8 | Notas / Keep | Aparece e inserta | |
| 9 | Chrome, campo de un formulario web | Aparece; ¿inserta? (puede usar el pegado de respaldo) | |
| 10 | Gmail (redactar) | Aparece e inserta | |
| 11 | Girar la pantalla | El botón se reubica sobre el teclado | |
| 12 | Teclado flotante o dividido (si usás) | Comportamiento razonable | |
| 13 | Dictar sin conexión | Mensaje "Sin conexión con el servicio" | |
| 14 | Latencia (segundo toque → texto) | Anotar segundos para 5 s y 15 s de audio | |

## Riesgo principal: el micrófono en segundo plano

Android restringe el uso del micrófono a apps que no están en primer plano, y a veces lo "silencia" entregando ceros en vez de un error. El código detecta ese caso (audio con ceros exactos) y muestra "El sistema bloqueó el micrófono".

**Si aparece ese mensaje**, el plan B, en orden:
1. Que el servicio inicie un servicio en primer plano de tipo micrófono mientras graba (con su notificación). Verificar en la documentación de Android qué se exige en tu versión.
2. Si no alcanza, evaluar ceder la grabación a una actividad transparente de corta vida que se abre al pulsar el botón.
3. Si ninguna funciona de forma fiable, reconsiderar la opción B (teclado), donde el micrófono funciona sin estas restricciones.

Anotá en `docs/ROADMAP.md` (tabla "Decisiones tomadas") lo que resulte.

## Privacidad (importante antes de cualquier uso comercial)

- El servicio declara que **no** es una herramienta de accesibilidad (no se marca `isAccessibilityTool`).
- Lee del sistema solo: si el foco está en un campo editable, no es contraseña, y dónde está el teclado. No registra ni guarda el contenido de pantalla.
- El texto que se inserta en el portapapeles (solo en el método de respaldo) se marca como sensible y el portapapeles se restaura a los 600 ms.
- Google Play revisa con atención el uso de Accesibilidad en apps que no son de accesibilidad. Verificá su política vigente antes de planear la publicación.
