# Recursos de Google Play

Recursos listos para la ficha `es-PE` de FacturaStock:

- `play-icon-512.png`: icono Play de 512 × 512 px, PNG RGBA y menos de 1 MiB.
- `feature-graphic-1024x500.png`: gráfico de funciones de 1024 × 500 px, PNG sin alfa.
- `es-PE/phone-screenshots/`: ocho capturas JPEG de 1080 × 1920 px.

Las capturas salieron de la app ejecutada en un Pixel 10a virtual. El recorrido utilizó únicamente
el modo demostración y la factura sintética canónica de 38 líneas. El recorte elimina la barra de
estado y mantiene la UI de la app sin marcos ni textos promocionales superpuestos. Los PNG originales
se conservan en `source/raw-screenshots/` para poder auditar el origen.

La revisión de cabecera muestra `20111111112`, número que SUNAT publica expresamente como RUC de
ejemplo en su
[anexo técnico de libros electrónicos](https://spij.minjus.gob.pe/Graficos/Peru/2010/diciembre/23/R-329-2010-SUNAT-Anexo%202.pdf);
no procede de una persona, proveedor ni comprobante del equipo. El resto del fixture está marcado
`[DEMO]` y `SIN VALOR TRIBUTARIO`.

Orden recomendado de carga:

1. `02-vista-previa.jpg`
2. `03-revision-cabecera.jpg`
3. `04-lineas-38.jpg`
4. `05-vinculacion.jpg`
5. `06-resumen.jpg`
6. `08-inventario.jpg`
7. `09-historial.jpg`
8. `01-inicio.jpg`

Antes de subir los recursos:

```bash
ruby scripts/verify-play-assets.rb
```

La procedencia y el prompt del único fondo generado están en `GENERATION.md`. El icono y las
capturas no se generaron con IA.
