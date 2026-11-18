# CapillaFlix · Android TV

Aplicación nativa en Kotlin para Android TV 8.0 (API 26) o superior. Jetpack Compose para TV, Media3/ExoPlayer y navegación con mando. No necesita cuenta, servidor propio ni clave de YouTube.

## Actualizaciones desde la TV

El botón **Actualizaciones** permite configurar un origen HTTPS, comprobar versiones cada seis horas mientras se usa la app, descargar con progreso y verificar el APK antes de abrir el instalador de Android. Desde la versión 1.2, el origen predeterminado es `https://github.com/jotapriet/capillaflix/releases/latest/download/latest.json`. Debe publicarse una release con ese archivo para que la comprobación funcione. Consulta [la guía de publicación](updates/README.md) para conservar la firma y generar `latest.json` con `tools/New-UpdateManifest.ps1`.

## Compilar e instalar

Abre este directorio en Android Studio y sincroniza Gradle. El proyecto usa el soporte integrado de Kotlin de AGP 9.5.1, Gradle 9.6 y el JDK 25 configurado en `gradle/gradle-daemon-jvm.properties`. Se necesita Android SDK 37.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El APK debug está firmado para instalación directa. Aparece en el lanzador de Android TV como **CapillaFlix**. Para distribución privada optimizada, usa `:app:assembleRelease` y firma el APK con tu clave desde Android Studio (Generate Signed APK). El APK release generado por Gradle sin configurar una clave no está firmado.

## Uso

Las televisiones tienen 30 segundos de timeout de conexión y lectura y una ventana de reintentos de 30 segundos por carga fallida, incluidos rechazos HTTP rápidos como el 403 intermitente de PTV. Los reintentos se espacian entre uno y tres segundos. Durante la espera se muestra «Conectando…». Los errores definitivos de formato/decodificación conservan su tratamiento inmediato. Una petición que siga esperando puede prolongar el tiempo total por encima de 30 segundos: la ventana de recuperación no es un cronómetro global del reproductor. No se garantiza la recuperación si el servidor sigue rechazando la emisión.

- Arriba/abajo: cambiar entre televisión, vídeos y radio.
- Izquierda/derecha: recorrer cada fila. Las tres filas permanecen en pantalla; los catálogos largos se desplazan horizontalmente.
- OK: reproducir televisión/radio o abrir un vídeo en YouTube para TV. Si no está instalado, se intenta abrir el enlace con otra aplicación disponible.
- Atrás: cerrar el reproductor y volver a la tarjeta seleccionada.
- En televisión, OK muestra los controles nativos de Media3; se manejan con la cruceta. El vídeo conserva su proporción, con bandas cuando la fuente no sea 16:9.
- En televisión, **P+/P− (CH+/CH−)** cambia de canal siguiendo el orden de la pantalla principal, con vuelta circular entre primero y último. El nombre aparece durante tres segundos. También funciona mientras conecta o muestra un error. Al volver al menú, el foco queda en el último canal seleccionado. Las teclas izquierda/derecha mantienen su función en los controles del reproductor. El mando debe enviar los códigos Android `KEYCODE_CHANNEL_UP`/`KEYCODE_CHANNEL_DOWN`; algunos televisores reservan esas teclas para su sintonizador y no las entregan a las aplicaciones.
- En radio, OK activa los botones de reproducción, pausa y regreso.
- Actualizar recarga YouTube. También se consulta al volver a la app y cada dos minutos mientras está visible.

La pantalla ocupa el televisor completo, sin barras del sistema. Los márgenes interiores protegen los textos en televisores con overscan.

## Catálogo y fuentes

`Catalog.kt` contiene los cuatro canales de televisión, las dos radios prioritarias y los 14 identificadores de canales de YouTube. Se ha eliminado la entrada duplicada de Arco del Postigo. `assets/youtube_channels.json` incluye 13 identificadores públicos resueltos para evitar consultas adicionales al arrancar. `@Blogsietepalabras` devuelve HTTP 404 a fecha de verificación; la app intenta resolverlo y muestra un aviso de feed parcial si continúa fallando.

`assets/radios.json` contiene las 39 emisoras publicadas en [Radio Garden Sevilla](https://radio.garden/visit/seville/6DT6YRBu/channels), con 36 redirecciones resueltas a sus servidores de audio. Para las otras tres se conserva el endpoint de Radio Garden. Onda Cofrade y Radio del Cofrade se anteponen: total 41 emisoras.

Fuentes de televisión contrastadas con los reproductores oficiales el **17 de noviembre de 2026**:

| Canal | Fuente oficial | Resultado desde esta conexión |
| --- | --- | --- |
| PTV Sevilla | [Web](https://www.ptvtelecom.com/canales/sevilla.html), [configuración JWPlayer](https://cdn.jwplayer.com/v2/media/jYUwdup4) | La URL HLS oficial devuelve 403 |
| María Visión Sevilla | [Web](https://www.mvsevilla.com/livemvsevilla) y HTML incrustado de Wix | HLS responde 200 |
| Sevilla 7TV | [Web](https://www.7tvandalucia.es/sevilla/sevilla-7tv-directo/20250723144948001214.html) | HLS responde 200 |
| 101TV Sevilla | [Web](https://101play.tv/es/pl/362), [configuración pública](https://capi.cdnmedia.tv/json/player/72c6611c) | HLS oficial devuelve 403, también con Referer |

Radio del Cofrade responde con audio MPEG en `https://sonic.sistemahost.es/8066/stream`. El enlace de Onda Cofrade facilitado (`https://stream.zeno.fm/pejcqbefxkzvv`) devuelve 401 desde esta conexión. Se conserva ese enlace sin sustituirlo por otra emisora. Estos resultados no acreditan reproducción en un televisor: dependen también del servidor, la red y la ubicación. Los fallos de reproducción muestran un mensaje y botones de reintento y regreso.

## Feed de YouTube

Los Shorts se excluyen cuando el enlace `alternate` del feed Atom apunta a `/shorts/`. El filtro se aplica también al RSS guardado para uso sin conexión. La caché general usa una clave nueva para evitar recuperar Shorts de versiones anteriores. No se filtra por duración ni por el título del vídeo, y no se añaden peticiones de red. Si un canal publica únicamente Shorts entre sus últimas entradas, puede no aportar vídeos al listado.

Se descargan los últimos vídeos disponibles en el feed Atom público de cada canal (habitualmente 15), se mezclan por fecha de publicación descendente y se eliminan duplicados. Se consulta además la pestaña de emisiones de cada canal: sólo los vídeos con indicador `LIVE` se anteponen. Las emisiones programadas o terminadas no se consideran directos activos. Los vídeos se abren en la app de YouTube; no se extraen sus archivos multimedia.

La caché permite ver el listado previo sin conexión. Los indicadores de directo no se guardan para evitar mostrar emisiones finalizadas como activas tras reiniciar. Las consultas se limitan a cuatro canales simultáneos y tienen tiempos de espera. Un fallo de canal no bloquea los demás. Se usa la preferencia anónima `SOCS=CAI` para consultar las páginas sin personalización. La detección de directos depende del HTML público de YouTube y puede necesitar mantenimiento si cambia su formato; no ofrece la garantía de una integración con API oficial.

## Archivos principales

La identidad visual utiliza un incensario dorado sobre azul oscuro. El icono adaptativo, el banner de Android TV y el símbolo de la cabecera son vectores Android. `tools/Generate-Brand.ps1` regenera estos recursos y las vistas previas `docs/logo-icon.png` y `docs/logo-banner.png`; el texto del banner está convertido a contornos para conservar su aspecto sin depender de fuentes instaladas en el televisor.

- `MainActivity.kt`: pantalla principal, filas, restauración de foco y apertura de YouTube.
- `PlaybackScreen.kt`: reproducción Media3, audio focus, gestión de ciclo de vida, controles y errores.
- `Catalog.kt`: catálogo, RSS, caché, detección de directos y ordenación.
- `FeedTest.kt`: pruebas de prioridad, deduplicación y formatos de respuesta de YouTube.

No hay servicios de reproducción en segundo plano: al salir de la app se pausa la emisión; al cerrar el reproductor se libera ExoPlayer. Los streams HTTP se permiten porque algunas radios públicas todavía los utilizan.

## Validación

Compilaciones debug y release, cuatro pruebas unitarias y Android Lint completados. Lint presenta advertencias de versiones disponibles y de una comprobación de terceros incompatible con AGP, sin errores bloqueantes. En el emulador Android 37 instalado, configurado a 16:9, se verificaron arranque, carga real de miniaturas y vídeos, navegación con D-Pad, reproducción de Radio del Cofrade y regreso con restauración del foco. La prueba de 7TV recibió vídeo H.264, pero el decodificador `c2.goldfish.h264.decoder` del emulador falló; no se ha acreditado reproducción de televisión en hardware físico. El reproductor permite decodificadores alternativos si falla la inicialización del principal.
