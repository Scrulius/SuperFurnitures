# SuperFurnitures — Tutorial completo

El plugin tiene **dos caras** que comparten motor:

1. **Gestor de modelos block-display** (`/bde`, admin) — descargar modelos de
   [block-display.com](https://block-display.com), colocarlos como decoración del server,
   moverlos con precisión y animarlos.
2. **Sistema de muebles para jugadores** (`/sf` + `/furniture`) — convertir esos modelos en
   **items colocables** (nativos del plugin o de MythicMobs) que los jugadores colocan, usan
   (sentarse/menús/comandos), giran y recogen, con protección y límites.

Regla mental: **`/bde` = decoración del admin** (modelos "sueltos" gestionados por ti);
**muebles = objetos de jugador** (con dueño, indestructibles, sobreviven solos con el chunk).

---

## Parte 1 — La library: la base de todo

Los modelos vienen de block-display.com. Cada uno tiene un **id** en la web (la parte final
de la URL del modelo). El flujo SIEMPRE empieza descargándolo a tu library local:

```
/bde download <id_de_la_web> <NombreLocal>     ← lo guarda en plugins/SuperFurnitures/library/
/bde library                                    ← lista lo descargado
/bde undownload <NombreLocal>                   ← lo borra de la library
/bde clearcache                                 ← limpia la caché de la API (si la web actualizó un modelo)
```

La library es **offline**: una vez descargado, el server nunca vuelve a depender de la web
(ni para re-spawns ni para muebles).

## Parte 2 — Modelos admin (`/bde`): decoración del server

Para estatuas, carteles 3D, decoración de spawn… cosas que gestionas tú directamente:

```
/bde spawn <id|NombreLocal> <nombre>   ← lo coloca donde estás (nombre único, 2-32 chars)
/bde list                              ← todos los modelos activos
/bde info [nombre|nearest]             ← detalles (piezas, posición, animación)
/bde tp [nombre|nearest]               ← teletransportarte a él
/bde move <dir> <dist> [nombre]        ← moverlo fino: up/down/north/south/east/west
                                         + left/right/forward/back (relativos a TU mirada)
                                         y distancias decimales (0.1, 0.05…)
/bde rotate <yaw> [nombre|nearest]     ← girarlo a un yaw exacto
/bde remove [nombre|nearest]           ← quitarlo
```

**Animaciones** (si el modelo trae):

```
/bde anim list [nombre]                ← qué animaciones tiene
/bde anim play <loop|once> [nombre] [animación]
/bde anim stop [nombre]
/bde speed <0.25-4.0> [nombre]         ← velocidad de la animación
```

**Mantenimiento**:

```
/bde purge <1-10>     ← mata TODAS las display entities en el radio (limpia modelos rotos).
                        Los muebles de jugadores VIVOS son inmunes; los restos huérfanos no.
/bde reload           ← recarga config.yml
```

Los modelos admin persisten en `data/<grupo>.json` y se re-spawnean solos al arrancar.
`nearest` siempre vale como nombre = "el más cercano a mí".

## Parte 3 — Crear un mueble de cero (el pipeline completo)

### Paso 1: el modelo

```
/bde download abc123 Silla
```

### Paso 2: el item (nativo o MythicMobs)

**Opción A — item NATIVO (recomendado, sin MythicMobs):** lo define el propio `furniture.yml`
en la sección `item:` del mueble (paso 3). El plugin lo crea, lo reconoce por su tag interno
(PDC) y lo devuelve al recoger. Lo ideal es una **player head con textura base64** (de
minecraft-heads.com) para que el item ya "parezca" el mueble en el inventario — apariencia
100% custom sin resource pack.

**Opción B — item de MythicMobs:** en `plugins/MythicMobs/Items/`:

```yaml
SillaItem:
  Id: PLAYER_HEAD
  Display: '&6Silla de madera'
```

No necesita opciones especiales: el plugin intercepta el clic antes de que el item actúe.
Si un mueble define las dos opciones, el plugin da/devuelve el nativo, pero los items MM
antiguos en circulación siguen funcionando (migración suave).

### Paso 3: la entrada en `plugins/SuperFurnitures/furniture.yml`

```yaml
furniture:
  silla:
    model: Silla              # nombre en la library (paso 1)
    item:                     # item nativo (opción A)…
      material: PLAYER_HEAD   #   (material opcional si hay head-texture)
      name: "&6Silla de madera"
      lore: [ "&7Clic derecho para colocarla" ]
      head-texture: "eyJ0ZXh0dXJlcyI6..."
      glow: false
    # mythic-item: SillaItem  # …o item MM (opción B)
    anchor: floor             # floor | wall | ceiling
    solid: true               # true = colisión real (bloques barrier invisibles)
    animated: false           # true = anima en loop mientras esté colocada
    permission: ""            # permiso extra opcional (además del global de colocar)
    hitbox: { width: 0.8, height: 1.0 }   # la "caja" clicable
    footprint: []             # celdas del caparazón sólido; vacío = columna del origen.
                              # NO lo escribas a mano: usa /sf footprint (abajo)
    interaction:
      type: seat              # none | seat | menu | commands
      seats: [ "0.0,0.4,0.0" ]   # plazas; tampoco a mano: usa /sf seats (abajo)
      # menu: mi_menu            # (type: menu) → dm open mi_menu <jugador>  (DeluxeMenus)
      # commands:                # (type: commands) — cooldown 1s anti-spam incluido
      #   - "player: warp tienda"
      #   - "console: give {player} bread 1"
    sounds:
      place: block.wood.place
      pickup: block.wood.break
```

```
/sf reload     ← debe decir "1 mueble(s)" — los errores de config salen AHÍ MISMO (y en consola)
/sf types      ← verifica el binding modelo↔item
/sf check      ← chequeo de salud completo: catálogo, items irresolubles, índice
```

### Paso 4: colócalo y afínalo MIRÁNDOLO (los 3 editores in-game)

Date el item (`/sf give silla`), colócalo, y a menos de 5 bloques:

**`/sf show`** — radiografía 10s: hitbox en **cian**, barreras en **rojo** (solo la ves tú).

**`/sf hitbox <ancho> <alto>`** — cambia la caja clicable y lo aplica EN VIVO a todos los
colocados (los de chunks descargados se alinean solos al cargar). Re-lanza el show para verlo.

**`/sf footprint`** — pinta el caparazón sólido **a golpes**:
- las celdas actuales se iluminan en naranja;
- **golpea un bloque** → celda añadida; golpéalo otra vez → quitada;
- `/sf footprint add` → celda bajo tus pies (para huecos en el aire);
- `/sf footprint clear` / `save` / `cancel`. Al guardar, el mueble que editas se
  re-acoraza en el acto; los demás colocados, al recolocarse.

**`/sf seats`** — el editor estrella, con **maniquís de verdad**:
- cada plaza actual muestra un maniquí SENTADO exactamente como quedaría un jugador;
- ponte de pie donde quieras una plaza, **mirando hacia donde miraría el jugador sentado**,
  y `/sf seats add` — tu posición Y tu mirada (snap 45°) se convierten en la plaza;
- afina mirando al maniquí: `/sf seats move <n> <x|y|z|yaw> <delta>` (ej: `move 2 y -0.2`);
- `/sf seats remove <n>` / `list` / `save` / `cancel`. Al guardar aplica YA a todos los
  colocados de ese tipo.

**`/sf info`** — chuleta del mueble que miras: dueño, piezas, barreras, hitbox, interacción.

## Parte 4 — Lo que hace el jugador

| Gesto | Efecto |
|---|---|
| **Clic derecho con el item** en un bloque | Coloca el mueble mirando hacia él (snap 90°). Consume el item. |
| **Clic derecho** al mueble | Lo usa: sentarse / abrir menú / ejecutar comandos (según el tipo). |
| **Agachado + golpe** | Lo **gira** (solo el dueño): 45° si no es sólido, 90° si lo es. |
| **Agachado + clic derecho con la mano vacía** | Lo **recoge** (solo el dueño): vuelve el item. |
| **Clic derecho** en un mueble decorativo | Te dice de quién es ("Silla — de Pepe"). |
| Clic en un **sofá multi-plaza** | Te sienta en la **plaza más cercana a ti**. |
| `/furniture list` | Sus muebles y dónde están. |
| `/furniture limit` | Su cuota usada/total. |

Protecciones automáticas: no se puede colocar sin permiso, en mundos vetados, sin espacio,
superando el límite (por jugador o por chunk) ni **en regiones protegidas** — y con muebles
sólidos se valida **cada celda** del caparazón, no solo el bloque clicado. Una vez colocado es
indestructible (TNT, pistones, fuego, mobs) — solo sale recogiéndolo. Al levantarse de un
asiento dentro de un mueble sólido, el plugin te saca al primer hueco libre (sin asfixias).

## Parte 5 — Administración del día a día

```
/sf gui                 ← GUI de TODOS los muebles del server: clic = teleport, shift+clic = recoger
/sf gui <jugador>       ← lo mismo filtrado a un jugador
/sf place <mueble>      ← mueble de SERVIDOR donde miras: sin dueño ni límites, nadie lo recoge
                          (solo admin), pero asientos/menús FUNCIONAN — bancos del spawn
/sf near [radio]        ← muebles a ≤N bloques, el más cercano primero; CLIC en la línea = tp
/sf audit [jugador] [n] ← últimas operaciones del registro (quién colocó/recogió/giró qué y dónde)
/sf list                ← resumen de TODOS los muebles del server, por dueño (texto)
/sf list <jugador>      ← el detalle de uno (tipo + coordenadas + mundo)
/sf check               ← chequeo de salud: errores de catálogo, items rotos, índice
/sf purge <jugador|server> ← borra todos los de un dueño (server = los de /sf place)
/sf give <mueble> [jugador] [cantidad]
```

Todo queda auditado en `furniture-log.jsonl` (rota solo a los 2 MB), y al arrancar el server
la consola resume la salud del módulo (tipos, errores de config, colocados, huérfanos).

Límites finos: además del global, cada mueble admite `max-per-player: N` en furniture.yml
(ej. máximo 2 del mueble-tienda por jugador).

**Auto-mantenimiento que no tienes que hacer tú**: el índice (`placements.json`) se cura solo
al cargar chunks — si un admin mató entities a mano, la entrada huérfana se poda; si el json
se perdió, los muebles vivos se re-adoptan. `/bde purge` nunca toca muebles vivos, y SÍ limpia
los restos huérfanos.

### Permisos

| Permiso | Default | Qué da |
|---|---|---|
| `superfurnitures.use` | todos | `/furniture` |
| `superfurnitures.place` | todos | colocar muebles |
| `superfurnitures.limit.N` | — | sube el límite personal a N (gana el mayor) |
| `superfurnitures.admin` | op | `/sf` entero (give, editores, purge…) |
| `superfurnitures.admin.bypass` | op | recoger/girar muebles ajenos |
| `superblocksdisp.use` | op | `/bde` entero |

### Límites y ajustes (en `furniture.yml`)

```yaml
limits:
  per-player-default: 20   # cuota base por jugador
  per-chunk: 12            # tope por chunk (anti-lag)
disabled-worlds: []        # mundos sin muebles
max-parts-warn: 100        # aviso si un modelo es demasiado pesado
```

## Chuleta final: de cero a mueble en 6 comandos

```
/bde download abc123 Silla        ← 1. modelo a la library
(entrada "silla" en furniture.yml ← 2+3. item nativo (item:) + binding, todo en un sitio
 con su sección item:)
/sf reload                        ← 4. cargar
/sf give silla                    ← 5. dártelo y colocarlo
/sf seats → add → save            ← 6. plazas con maniquís (y /sf footprint, /sf hitbox si toca)
```

Y la guía de pruebas con los comportamientos esperados de cada cosa está en
[`TESTING_MUEBLES.md`](TESTING_MUEBLES.md).
