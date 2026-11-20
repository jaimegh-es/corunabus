# Coruña Bus para Nokia E71 (J2ME)

MIDlet MIDP 2.0 / CLDC 1.1 para el Nokia E71 y cualquier S60 3rd edition.
El móvil no calcula rutas: pide texto plano al worker.

## Descargar e instalar

1. Descarga `corunabus.jar` y `corunabus.jad` del último artefacto del workflow
   **J2ME (Nokia E71)** en GitHub Actions (o de la release `j2me-v…`).
2. Copia los dos ficheros a la misma carpeta de la tarjeta de memoria.
3. Abre `corunabus.jar` (o el `.jad`) desde el Administrador de archivos.
   Si pide firma, acepta la instalación sin firmar.

## Uso

- **Paradas cercanas**: usa el GPS interno (JSR-179). Si no hay posición, usa
  la búsqueda.
- **Buscar parada**: tolera acentos, mayúsculas, puntuación y orden. Escribe
  `manuel azaña 27` y encuentra `Manuel Azaña, 27`.
- **Favoritos**: se guardan en el propio teléfono (RMS), sin cuenta.
- **Planificar ruta**: elige origen y destino de los favoritos o búscalos; el
  cálculo lo hace el servidor y llega ya resuelto.
- **Ajustes**: dirección del servidor y `Datos` para recargar la lista de
  paradas. El catálogo se descarga una sola vez (~27 KB) y se reutiliza hasta
  que pulses ese botón.

