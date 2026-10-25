# Mi TV — reproductor IPTV (Xtream Codes) para Android TV

App simple en Kotlin + Media3/ExoPlayer, pensada para que los canales no se peguen:
buffer grande configurable, reconexión automática, selector de calidad cuando el canal
lo permite y estadísticas en pantalla para saber qué está fallando.

## Compilar e instalar

1. Abre la carpeta `MiTV` con **Android Studio** (Archivo → Open). Usa el JDK 17 que trae incluido.
2. Espera a que sincronice Gradle (la primera vez descarga dependencias).
3. Menú **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
5. Instálalo en el TV box:
   - Por red: activa "Depuración ADB" en el box, luego `adb connect IP_DEL_BOX:5555` y
     `adb install app-debug.apk`.
   - O copia el APK al box (USB, o app "Send Files to TV") y ábrelo con un explorador de archivos.

## Uso

- Primera vez: servidor (`http://liontv.es:8080`), usuario y contraseña. Se guardan solo en el dispositivo.
- Categorías a la izquierda, canales a la derecha, buscador arriba.
- En el reproductor:
  - **OK / Enter**: muestra la barra superior (nombre del canal + botón Opciones).
  - **Arriba / Abajo** (con la barra oculta) o **CH+ / CH−**: cambia de canal.
  - **Menú / Info**: abre Opciones directo.
  - **Atrás**: vuelve a la lista.

## Opciones del reproductor

| Opción | Qué hace |
|---|---|
| Calidad de video | Lista las variantes que ofrezca el canal (solo HLS multi-calidad). Si el proveedor envía un solo stream, avisa que no hay nada que cambiar. |
| Formato | `M3U8` (HLS) o `TS`. Si falla 3 veces, la app prueba el otro formato sola. |
| Buffer | Normal (arranca en ~3 s), Grande (~10 s), Máximo (~20 s). Más buffer = más retraso al arrancar, pero aguanta mejor los cortes. |
| Estadísticas | Muestra buffer disponible, retraso, resolución, fotogramas descartados y estado. |
| Pantalla | Ajustar / Zoom / Llenar. |

## Cómo leer las estadísticas

- **Buffer baja a 0 s seguido** → problema de red o del servidor (Wi-Fi, ISP, el proveedor).
- **Buffer lleno pero "Descartados" sube rápido** → el decodificador del box no da abasto
  (prueba otro formato, una calidad menor o un box más potente).
- **Reconecta con códigos de error HTTP** → el servidor rechaza o corta la conexión
  (límite de conexiones de la cuenta, por ejemplo).

## Notas

- Es una app de uso personal: la clave se guarda en texto plano en el almacenamiento privado de la app.
- Si la app se cierra sola con el buffer "Máximo", baja a "Grande": cada segundo de buffer ocupa memoria RAM.
- Las URLs son HTTP (sin cifrar), por eso el manifest permite tráfico `cleartext`.
- Para agregar logos de canales, favoritos o guía EPG, se puede extender: la API Xtream ya los entrega.
