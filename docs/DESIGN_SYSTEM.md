# Sistema visual de FacturaStock

El diseño está optimizado para registrar información con rapidez en un teléfono, con
ruido, movimiento o iluminación variable. Se evita presentar una tabla de escritorio
reducida: cada entidad se muestra como tarjeta y las ediciones extensas deben usar una
pantalla completa o un *bottom sheet*.

## Tema y tokens

- La marca utiliza azul cobalto/navy como color primario, neutros grafito y un acento
  cobre discreto. Ofrece esquemas Material 3 claro y oscuro sin grandes superficies
  verdes de marca.
- Éxito, advertencia e información tienen colores semánticos propios; los errores usan
  los roles `error` de Material. Ningún estado depende exclusivamente del color: siempre
  incluye texto y, cuando corresponde, iconografía.
- Los colores crudos viven en `ui/theme/Color.kt`. Las features consumen
  `MaterialTheme.colorScheme` o `FacturaStockDesign.semanticColors`.
- La escala de espaciado es 4, 8, 12, 16, 24, 32 y 48 dp. Las formas principales usan
  radios de 8, 12, 20, 28 y 32 dp.
- La tipografía usa la fuente del sistema y unidades `sp`; no se descarga ni fuerza una
  fuente que pueda ignorar las preferencias de accesibilidad.

## Componentes

`ui/components` contiene barra superior, navegación inferior, botones primario y
secundario, tarjeta de estado, chip de confianza, error recuperable, carga, estado vacío,
diálogo, resumen monetario fijo y un scaffold con ancho de contenido limitado para
pantallas grandes. Son componentes sin estado de negocio: reciben texto ya localizado,
recursos visuales, tono semántico y callbacks. Los umbrales de confianza se deciden en
la feature; el sistema visual únicamente representa el tono recibido.

El resumen monetario recibe tanto el valor visual como una frase apta para lector de
pantalla. Desde 150 % de escala de fuente cambia de fila a columna para conservar completa
la cantidad y mantener visible la acción.

## Accesibilidad obligatoria

1. Todo control interactivo tiene un área mínima de 48 × 48 dp.
2. Los botones se nombran mediante su etiqueta visible. Los botones que muestran solo un
   icono reciben una descripción de acción localizada; los iconos decorativos usan una
   descripción nula para no duplicar anuncios.
3. Los títulos principales usan semántica de encabezado. Diálogos usan `paneTitle`, los
   estados dinámicos usan regiones vivas prudentes y la navegación expone rol de pestaña
   y selección.
4. El contenido principal es desplazable. No se fijan alturas en contenedores con texto;
   los botones del diálogo se apilan y ocupan todo el ancho.
5. Las previews incluyen modo claro, oscuro y fuente al 200 % sobre un teléfono de
   360 × 800 dp.

La tarea `verifyUiConventions`, ejecutada antes de compilar, rechaza textos visibles
escritos directamente en composables, colores crudos fuera del tema y colores XML fuera
de `values/colors.xml`. La validación final debe incluir Lint y una pasada manual con
TalkBack y el tamaño de fuente del sistema al 200 %.
