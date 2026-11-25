# CooauCAM

App Android para controlar la cámara de acción **COOAU CU-SPC05** desde el móvil, sin la app iSmart DV2.
Proyecto de JM Prestige AI Solutions.

## Lo que sabemos de la cámara
| Puerta | Uso |
|---|---|
| 192.168.1.1:554 | Vídeo en directo RTSP (H.264, iCatchTek) |
| 192.168.1.1:15740 | Control PTP/IP (chip iCatch V37) |
| 192.168.1.1:21 | FTP (archivos) |

Wi-Fi: `CU-SPC05_xxxxxx`, clave `1234567890`.

Ajustes leídos: 5001 batería, 5011 fecha/hora, D605 resolución vídeo, 5003 tamaño foto, 501E chip, 501F firmware.

## Descargar
Última versión: **Releases → CooauCAM-vX.Y.apk** (GitHub la fabrica sola en cada cambio).

## Versiones
- **0.1** – Conectar, directo, batería/tarjeta, poner hora, botones de prueba A–E para descubrir la orden de grabar/parar.
- **0.2** – Cada prueba comprueba sola si ha aparecido una foto o vídeo nuevo en la tarjeta; botón Archivos; arreglos de pantalla.
- **0.3** – Modo seguro: tras la orden A no se pregunta nada a la cámara (se colgaba al leer la tarjeta mientras grababa); directo solo bajo demanda.
- **0.4** – La prueba A ya no cierra el directo; el registro indica si el directo estaba abierto. Confirmado: la orden 100E pone la cámara a grabar vídeo (archivo 20261101_162916.MOV).
