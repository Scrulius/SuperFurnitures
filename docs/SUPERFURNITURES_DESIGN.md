# SuperFurnitures — Diseño (2026-06-11)

> ✅ **IMPLEMENTADO (v1.2.0, 2026-06-11)**: F1+F2+F3 completas según este documento
> (paquete `com.blockdisplay.plugin.furniture`: Registry, Manager, Listener, PlacementIndex,
> MythicHook reflectivo, SfCommand, FurnitureCommand; plugin renombrado con migración de
> data folder). Pendiente: prueba en vivo en el server (crear un item MM + entrada en
> furniture.yml y colocar/recoger/sentarse).

SuperBlocksDisplays evoluciona a **SuperFurnitures**: el motor de displayblocks (spawn, snapshots,
animación nativa, hitboxes) pasa de herramienta de admin a sistema de **muebles para jugadores**.
`/bde` sigue dentro como herramienta admin. Nicho: furniture **100% vanilla sin resource pack**
(block-displays de block-display.com) — lo que MythicCrucible NO puede hacer (usa CMD+pack).

## Decisiones cerradas (con el autor, 2026-06-11)

| Tema | Decisión |
|---|---|
| Arquitectura | Evolucionar SuperBlocksDisplays, renombrado **SuperFurnitures** (repo incluido) |
| Colisión | Configurable por mueble (`solid: true` → barriers invisibles en la huella) |
| Recoger | **Shift + clic derecho con mano vacía** → devuelve el item MM al inventario |
| Propiedad | Dueño en PDC + protección: solo el dueño (o bypass admin) recoge; colocar respeta WorldGuard |
| Interacciones | **Sentarse**, **menú DeluxeMenus**, **comandos configurables** (storage NO en v1) |
| Rotación | 4 direcciones (snap N/S/E/W mirando al jugador) |
| Límites | Por jugador (permiso `superfurnitures.limit.N`) **y** por chunk |
| Obtención | Cosa del admin (`/sf give`); economía fuera del plugin (DeluxeMenus shop, crates…) |
| Anclaje | `floor` / `wall` / `ceiling` por mueble (default floor) |
| Animación idle | `animated: true` por mueble → loop al colocarse (motor nativo ya existente) |
| Cmd jugador | `/furniture list` (tus muebles y dónde) + `/furniture limit` (cuota usada/total) |
| Item en mano | Item de MythicMobs; recomendar **player head con textura base64** (parece un mueble sin pack) |

## Arquitectura técnica

### Persistencia: el mundo ES la base de datos (≠ sistema /bde)
Los muebles **NO** usan el registro central + respawn de `/bde` (que borra entities en onDisable y
los respawnea del snapshot — no escala a cientos/miles). Los muebles son **entities persistentes
vanilla**: viven y se guardan CON el chunk. Toda la metadata viaja en PDC:
- Cada pieza: `furniture_instance` (UUID de la instancia).
- Una entity **ancla** (la Interaction): metadata completa — `furniture_type` (id de config),
  `owner` (UUID), yaw, lista de posiciones de barriers, UUIDs de las piezas.
- Cero coste al arrancar. Muebles animados se re-vinculan al cargar el chunk
  (`EntitiesLoadEvent` → leer PDC → registrar en AnimationManager).
- Los modelos admin de `/bde` mantienen su sistema actual sin cambios.

### Flujo de colocación
`PlayerInteractEvent` (right-click block, item MM en mano) →
`MythicBukkit.getItemManager().getMythicTypeFromItem()` → match con furniture registry →
validaciones en orden: permiso → WorldGuard `canBuild` → límite jugador → límite chunk →
anclaje válido (floor/wall/ceiling según cara clicada) → espacio libre →
spawn del modelo (motor existente, desde library) + Interaction hitbox → PDC → consumir 1 item →
sonido/partícula. Cooldown anti-spam (~500ms). Rotación: yaw del jugador snap a 90°.

### Flujo de recogida
Shift + right-click con **mano vacía** sobre la Interaction → check dueño (o
`superfurnitures.admin.bypass`) → marcar instancia "recogiéndose" (guard atómico anti-dupe) →
quitar piezas + barriers (solo si siguen siendo barriers) → devolver item MM
(inventario lleno → drop a los pies) → sonido.

### Hitbox de interacción
Si el modelo trae hitbox (interaction entities del autor) → se usa. Si no → el admin declara
`hitbox: {width, height}` en config y generamos la Interaction nosotros. La Interaction es el
"cuerpo" clickeable: `PlayerInteractAtEntityEvent` (right-click) / `EntityDamageByEntityEvent`
(left-click → cancelado, los muebles no se rompen a golpes).

### Sentarse
`interaction.type: seat` con offset configurable (y lista de offsets para sofás multi-plaza).
Implementación: pasajero sobre entity invisible en el offset (detalle a resolver en implementación;
candidatos: la propia Interaction, ArmorStand marker invisible).

### Solidez
`solid: true` → al colocar se calcula la huella (footprint declarado en config) y se ponen
barriers; al recoger se quitan SOLO los que sigan siendo barrier. Posiciones guardadas en el PDC
del ancla.

## Esquema de config (borrador)

```yaml
furniture:
  sofa_rojo:
    model: Sofa_Rojo              # nombre en la library (/bde download)
    mythic-item: SofaRojoItem     # item MM que lo coloca
    permission: superfurnitures.place.sofa_rojo   # opcional; ausente = solo permiso global
    anchor: floor                 # floor | wall | ceiling
    solid: false
    animated: false
    hitbox: { width: 2.0, height: 1.0 }   # solo si el modelo no trae la suya
    interaction:
      type: seat                  # seat | menu | commands | none
      seats:
        - [0.5, 0.6, 0.0]         # offsets de asiento (multi-plaza = varias entradas)
      # type: menu
      # menu: tienda_muebles      # → /dm open tienda_muebles {player}
      # type: commands
      # commands:
      #   - "player: warp casa"
      #   - "console: give {player} diamond 1"
    sounds: { place: block.wood.place, pickup: block.wood.break }

limits:
  per-player-default: 20          # superfurnitures.limit.N lo eleva (gana el mayor)
  per-chunk: 12
```

## Comandos y permisos
- `/sf give <mueble> [jugador]` (admin) — da el item MM del mueble.
- `/sf admin list <jugador>` / limpieza (a concretar).
- `/furniture list` + `/furniture limit` (jugador).
- `/bde ...` intacto (admin, modelos sueltos sin item).
- Permisos: `superfurnitures.place` (global), `.place.<id>` (por mueble, si declarado),
  `.limit.N`, `.admin.bypass`, `.admin`.

## Fases de implementación
1. **F1 núcleo**: registry de config + MythicMobs hook + colocar/recoger + dueño/WG + límites +
   rotación + anclajes + `/sf give` + `/furniture list|limit` + persistencia por chunk.
2. **F2 vida**: sentarse (multi-plaza), menús DeluxeMenus, comandos, sonidos/partículas.
3. **F3 pulido**: solid/barriers, animación idle re-vinculada por chunk, `/sf admin`, renombrado
   completo del plugin/repo.

## Preguntas finas — RESUELTAS (autor, 2026-06-11)
1. **Variantes de color** (sofá rojo/azul): entradas SEPARADAS en config, una por variante. Sin
   sistema de variantes.
2. **Muebles huérfanos** (se borró su entrada de config con unidades colocadas): **siguen siendo
   recogibles** por su dueño (no devuelven item) y al interactuar/recoger sale un mensaje claro
   "este mueble fue eliminado del catálogo" — el jugador siempre tiene feedback, nunca un mueble
   "muerto" que no responde.
3. **Blacklist de mundos**: SÍ — `disabled-worlds: []` en config (no se puede colocar ahí).
4. **Sanity check de tamaño**: SÍ — al registrar un mueble cuyo modelo pase del umbral
   (`max-parts-warn`, default ~100 piezas) → WARN en consola. Razón: el coste de un mueble
   ESTÁTICO es pasivo pero real — cada pieza es una entity que el cliente recibe (paquete por
   pieza al entrar en rango) y renderiza; el lag de furniture viene de UN modelo demasiado
   detallado × muchas unidades, no del sistema. Animado añade coste activo (tick + metadata
   continua), por eso `animated` es opt-in por mueble.
5. **Preview fantasma**: v2+, prioridad baja (el propio autor la ve complicada).
6. **Repo renombrado**: `Scrulius/SuperBlocksDisplays` → **`Scrulius/SuperFurnitures`** (GitHub
   redirige las URLs viejas). El renombrado del plugin/jar en sí llega con la F3.
