# Publicar actualizaciones privadas de CapillaFlix

La aplicación se distribuye fuera de Google Play desde el repositorio público [jotapriet/capillaflix](https://github.com/jotapriet/capillaflix). La versión 1.2 incorpora este origen predeterminado:

`https://github.com/jotapriet/capillaflix/releases/latest/download/latest.json`

La URL responderá correctamente cuando exista una release estable publicada con `latest.json` como archivo adjunto.

## Primera instalación

Instala manualmente el APK **1.2 (versionCode 3)**, que incluye la URL. Si ya instalaste la 1.1, abre **Actualizaciones > Configurar origen** e introduce esa URL para recibir la 1.2 cuando se publique. Si anteriormente guardaste un origen vacío para desactivar las comprobaciones, vuelve a guardar la URL: esa preferencia tiene prioridad sobre la predeterminada. También puedes cambiar el origen al compilar:

```powershell
.\gradlew.bat :app:assembleDebug '-Pcapillaflix.updateManifestUrl=https://github.com/jotapriet/capillaflix/releases/latest/download/latest.json'
```

La app comprueba esa URL al abrirse, como máximo cada seis horas, y permite buscar manualmente. Aparece «Nueva versión» en la cabecera cuando hay una actualización. No se descarga ni instala sin la acción del usuario. No interrumpe el reproductor.

## Siguientes versiones en GitHub Releases

1. Incrementa `versionCode` en `app/build.gradle.kts` (la 1.2 usa 3; la siguiente debe usar al menos 4) y cambia `versionName`.
2. Compila un APK **firmado con la misma clave** que la versión instalada. El APK debug funciona con los instalados desde este ordenador mientras se conserve su clave debug. El APK release sin firmar no sirve. Para distribución mantenida, conserva una clave propia y úsala desde la primera instalación de esa variante; Android no permite pasar de debug a otra firma como actualización normal.
3. Crea una release de GitHub (por ejemplo `v1.2`) y prepara la URL definitiva de su APK.
4. Genera el manifiesto desde ese APK, sin editar a mano su huella:

```powershell
.\tools\New-UpdateManifest.ps1 `
  -ApkUrl 'https://github.com/jotapriet/capillaflix/releases/download/v1.2/CapillaFlix.apk' `
  -Notes 'Cambios de esta versión'
```

5. Adjunta **el mismo APK** (con el nombre indicado en la URL) y el `updates/latest.json` generado a la release. Publícala como versión estable más reciente. Para un release firmado, pasa `-ApkPath` con su ruta; debe conservarse el `output-metadata.json` de Gradle en el mismo directorio.

El manifiesto tiene este formato (valores de ejemplo, no una actualización real):

```json
{
  "versionCode": 3,
  "versionName": "1.2",
  "apkUrl": "https://github.com/jotapriet/capillaflix/releases/download/v1.2/CapillaFlix.apk",
  "sha256": "HUELLA_SHA256_DE_64_CARACTERES_GENERADA_POR_EL_SCRIPT",
  "minSdk": 26,
  "notes": "Cambios de esta versión"
}
```

La app sólo ofrece versiones superiores. Descarga por HTTPS (hasta 150 MB), permite cancelar, muestra progreso cuando el servidor indica el tamaño y verifica SHA-256, identificador del paquete, versionCode, compatibilidad Android y firma antes de abrir el instalador. No admite rotación de clave de firma en esta primera implementación. La caché es privada y únicamente se comparte el APK con el instalador mediante FileProvider.

En Android 8+ puede ser necesario autorizar a CapillaFlix para **instalar aplicaciones desconocidas**. La app abre ese ajuste al pulsar Instalar y, si se concede el permiso, continúa al instalador. La instalación siempre requiere la confirmación del usuario en Android. Si el proceso se cierra mientras se está en los ajustes, vuelve a abrir la app y descarga de nuevo.

«Privadas» significa aquí distribución fuera de Google Play. Un repositorio **público** permite usar GitHub Releases directamente. Los archivos de un repositorio **privado** requieren una solución adicional de autenticación o un servidor con URLs de descarga accesibles; no se incrusta un token de GitHub en el APK.

Una URL vacía en Configurar origen desactiva las comprobaciones. Si se interrumpe o cancela la descarga, el archivo parcial se elimina. No se requiere permiso para escribir en almacenamiento compartido.
