# CLAUDE.md — SuperFurnitures (antes SuperBlocksDisplays)

Plugin Paper 1.21.x (Java 21, **Maven**: `mvn clean package` → `target/SuperFurnitures-*.jar`)
con dos caras: **muebles para jugadores** (items nativos o MythicMobs → muebles displayblock,
paquete `furniture/`, IMPLEMENTADO v1.2.0) y gestor admin de modelos block-display.com (`/bde`).
**Diseño y decisiones cerradas en [`docs/SUPERFURNITURES_DESIGN.md`](docs/SUPERFURNITURES_DESIGN.md)
— leerlo antes de tocar nada.** Claves furniture: entities persistentes vanilla + PDC en ancla
Interaction (el mundo es la BD, sin respawn al arrancar); MythicHook por REFLEXIÓN (no hay dep
Maven de MythicMobs); protección vía probe de `BlockPlaceEvent` sintético (respeta WorldGuard y
cualquier protección sin compilar contra ellas — ⚠️ el probe lleva un ItemStack de STONE neutro,
NUNCA el ítem MythicMobs real: MythicCrucible cancela cualquier `BlockPlaceEvent` de un ítem
mythic sin `Options.Placeable`, lo que vetaba TODA colocación, fix v1.2.1); asientos =
ArmorStand marker no-persistente;
el plugin renombrado migra `plugins/SuperBlocksDisplays` → `plugins/SuperFurnitures` al arrancar.

- Repo: **https://github.com/Scrulius/SuperFurnitures** (renombrado; push directo a `main`).
  Sube a GitHub al terminar cualquier trabajo. Mensajes de commit en español.
- **🚫 SIN co-autoría de Claude**: nunca añadas `Co-Authored-By: Claude ...` a los commits ni PRs
  (norma GLOBAL del autor — aplica a todos sus proyectos, presentes y futuros).
- Deploy: copiar el jar a `C:\Users\Knopp\Desktop\server paper testeos\plugins\` (borrar el jar de
  versión anterior si cambia el nombre). Reinicio completo, NUNCA hot-swap.
- Motor de animación NATIVO por API de Paper (`AnimationManager`): keyframes compilados a
  `setTransformationMatrix`/`setInterpolationDuration`/`setBlock` por UUID; matriz row-major de MC
  → `new Matrix4f().set(floats).transpose()` (⚠️ único punto pendiente de verificación visual; si
  un modelo sale deformado, quitar el transpose). Fallback por comando silencioso solo para
  payloads no mapeables (`item:`, residuo desconocido).
- Persistencia admin (`/bde`): snapshot local completo en `data/<group-id>.json` — el re-spawn
  NUNCA depende de la API. Los muebles de jugadores usarán otra arquitectura: entities
  persistentes vanilla + metadata en PDC (ver diseño).
- Piezas trackeadas por **UUID** (no referencias `Entity`, quedan stale al recargar chunk).
- **Items nativos sin MythicMobs (v1.4.0, `FurnitureItems`)**: cada mueble puede definir
  `item:` en furniture.yml (material, name/lore con & o MiniMessage, glow, `head-texture`
  base64 → PLAYER_HEAD con skin custom SIN resource pack) además de (o en vez de) `mythic-item`,
  que ahora es OPCIONAL — hace falta al menos uno de los dos. Identidad por PDC
  (`furniture_item` = type id; profile UUID estable por tipo para que stackeen). Reconocimiento
  en `onPlace`: PDC nativo primero, MM como fallback; un item nativo cuyo tipo salió del catálogo
  queda INERTE (se cancela el clic — una PLAYER_HEAD de mueble nunca debe colocarse como bloque
  cabeza vanilla). `FurnitureManager.itemFor(type)` centraliza "el item de un tipo" (nativo si hay
  spec, MM si no) y lo usan pickup/give/GUI. Sin MythicMobs el módulo YA NO se desactiva: solo
  caen los muebles con `mythic-item`. Si un tipo define ambos, se da/devuelve el nativo pero los
  items MM viejos siguen colocando (migración suave).
- **Verificaciones + admin in-game (v1.4.0)**: `/sf reload` muestra los errores de carga al
  sender (antes solo consola; `FurnitureRegistry.lastErrors()`); `/sf check` = doctor (errores de
  catálogo, items irresolubles, menu/commands vacíos, footprint con solid:false, índice: totales
  por mundo, mundos desaparecidos, tipos huérfanos colocados); `/sf gui [jugador]` = GUI admin de
  TODOS los muebles (o filtrado), clic = `teleportTo` (teleportAsync + rescueFromSuffocation),
  shift+clic = recogida remota (usa el bypass normal); `/sf give` acepta `[cantidad]` (1-64).
  El GUI de jugador y el admin comparten renderer (`FurnitureGui.render` con flag admin en el
  Holder). Al colocar, el actionbar muestra la cuota "(usados/límite)".
- **GUI de muebles del jugador (v1.3.0, `FurnitureGui`)**: `/furniture` (alias `/muebles`) sin
  args abre un GUI paginado (45/página, prev/info/next/cerrar en 48/49/50/53) con un icono por
  mueble colocado — el **ItemStack real de MythicMobs** (se ve el mueble), título = display name
  del item MM (fallback al id), lore con tipo/mundo/coordenadas/distancia — y **clic = recogerlo
  a distancia**: `FurnitureManager.pickupRemote` resuelve el anchor por instance id cargando el
  chunk (mismo patrón que `purgeOwner`) y reutiliza `pickup()` (chequeo de dueño, item de vuelta,
  sonidos); si el anchor ya no existe poda la entrada del índice. Huérfanos (tipo fuera del
  catálogo) salen como BARRier con aviso "no devuelve item". Anti-dupe: TODOS los clics/drags que
  tocan el top se cancelan (los iconos son items reales). Orden: mundo del jugador primero por
  distancia, luego otros mundos. `/furniture list|limit` siguen como salida de texto.
- **Asientos a prueba de fugas (v1.3.2)**: el bug real del usuario era el **stand de asiento**
  (no el maniquí): algún camino de desmontaje lo dejaba vivo, invisible y matable (soltaba XP —
  de otro plugin del server reaccionando al kill, los stands no dan XP vanilla). Cierre total:
  **watchdog cada 40 ticks** (`startSeatWatchdog`: cualquier stand de `activeSeats` sin pasajero
  muere en ≤2s, pase lo que pase con los eventos), `removeFurniture` barre además los asientos
  trackeados por instance (independiente del radio), `addPassenger` fallido elimina el stand en
  el acto, y el stand spawn con `setInvulnerable+setCollidable(false)`. ⚠️ NO depender solo de
  `EntityDismountEvent` para limpiar asientos — el watchdog es la garantía.
- **⚠️ Gotcha PowerShell 5.1 (encoding)**: NUNCA editar ymls UTF-8 del server con
  `Get-Content`/`Set-Content` (lee ANSI sin BOM y corrompe los acentos → SnakeYAML revienta con
  "unacceptable code point"). Usar las herramientas Read/Write o `[System.IO.File]` con UTF8.
  El `furniture.yml` del server se regeneró en ASCII puro tras corromperse así.
- **Pase anti-restos (v1.3.1)**: TODA entity del plugin lleva tag PDC y está triple-protegida —
  los previews del editor de asientos (maniquí + stand) llevan `keyPreview` (antes iban SIN tag,
  solo tracking en memoria: cualquier camino de limpieza perdido dejaba un maniquí matable — en
  creativo `setInvulnerable` no protege — que soltaba XP). `onDamage` cancela por
  keyInstance/keySeat/keyPreview; interact/attack a previews cancelado; `removeFurniture` barre
  también previews y stands sin instancia (recoger un mueble a media edición ya no deja maniquí);
  **janitor en `EntitiesLoadEvent`**: previews y seat-stands vacíos que aparezcan en un chunk
  cargado se eliminan (auto-cura mundos con restos antiguos). ⚠️ `pickupRemote`/`purgeOwner`:
  las entities del chunk cargan ASYNC tras `getChunkAt` — NUNCA podar el índice si
  `!chunk.isEntitiesLoaded()` (podaría muebles VIVOS → huérfanos); pickupRemote sostiene el chunk
  con ticket y reintenta hasta 3s (callback `onChanged` para refrescar el GUI).
- **`solid` es para muebles grandes que deban bloquear el paso**; sillas/decoración SIN barrier
  (estorba al clicar y al construir). El default ya era false; el `solid: true` de la silla de
  pruebas se quitó del server.
- Robustez post-v1.2.0: `/sf list [jugador]` (resumen global por dueño o detalle) y
  `/sf purge <jugador>` (`FurnitureManager.purgeOwner`, carga chunks bajo demanda); el índice
  (`placements.json`) se **auto-cura en `EntitiesLoadEvent`** (poda entradas sin anchor — admin
  hizo `/kill` a mano — y re-adopta anchors vivos que no conoce — json perdido); `/bde purge`
  exime SOLO muebles vivos (con entrada en el índice) → los restos huérfanos sí se purgan;
  anti-asfixia al desmontar asiento dentro de mueble sólido (`rescueFromSuffocation`, sube al
  primer hueco libre; también en quit ANTES de que guarde la posición); muebles menu/commands
  con cooldown 1s por jugador (anti-spam de comandos).
- **Herramientas de tuning in-game** (`SeatEditor` + `FurnitureVisualizer`): `/sf seats` =
  editor WYSIWYG de asientos — spawnea un **MANNEQUIN** (entity runtime 26.1.2; se resuelve
  por `EntityType.valueOf` porque el pom compila contra paper-api 1.21.1 que no la tiene;
  fallback armor stand con brazos) montado en el stand marker de cada asiento → se ve sentado
  EXACTAMENTE como un jugador. Flujo: ponerse de pie donde va el asiento → `add` (offset =
  posición del admin − 0.45 de caída al sentarse, inverso-rotado a espacio local), `move <n>
  <x|y|z> <delta>` para afinar mirando el maniquí, `save` escribe `interaction.seats` en
  furniture.yml (fuerza `interaction.type: seat`) + `registry.load()` → aplica al instante a
  todos los colocados. Previews no-persistentes, limpieza en quit/disable, sesión auto-cancela
  si el mueble desaparece. `/sf show` = wireframe de partículas DUST 10s (hitbox cian, barreras
  rojas, solo las ve el admin); se auto-lanza al abrir el editor. `/sf info` = radiografía del
  mueble cercano (dueño, piezas, barreras, hitbox, interacción, instancia).
- **Yaw por asiento**: los offsets de `interaction.seats` aceptan un 4º componente opcional
  (`x, y, z, yawRelativo` en grados, relativo al frente del mueble) — sofás en L con asientos
  mirando a sitios distintos. El editor lo captura de la MIRADA del admin al hacer `add`
  (snap 45°; 0° no se escribe) y tiene eje `yaw` en `move`. Aplicado en `FurnitureManager.seatYaw`.
- **`/sf footprint`** (`FootprintEditor`): pintar la huella sólida **a golpes** — punch togglea
  celdas (naranja, bucle de partículas por sesión), `add` marca el bloque bajo los pies (celdas
  en el aire), `clear`, `save` escribe `footprint` + `solid: true` y **re-aplica el caparazón
  del mueble editado** (`FurnitureManager.reshell`: barreras viejas→aire, nuevas según el tipo,
  PDC actualizado). ⚠️ Los demás colocados conservan su caparazón hasta recolocarse (se hornea
  al colocar).
- **`/sf hitbox <ancho> <alto>`**: persiste `hitbox.width/height` del tipo cercano, re-sincroniza
  en vivo TODOS los anchors cargados (`syncHitbox`) + reshell del cercano + auto-`show`; los de
  chunks descargados se alinean solos al cargar (sync de hitbox añadido al `EntitiesLoadEvent`).
- **Rotación in-place** (`FurnitureManager.rotateFurniture`): el DUEÑO gira su mueble 90°
  con **agachado + golpe** (las piezas viven todas en el origen con la forma en matrices →
  girar = yaw+90 por pieza; los hitboxes autorados con offset orbitan el eje). Pre-check de
  espacio del caparazón girado ANTES de tocar nada, eject de sentados, `keyYaw`+`reshell`,
  cooldown 400ms. WALL no se gira (recolocar). Hint actualizado: "agáchate: clic derecho =
  recoger, golpe = girar".
- **Colocación endurecida**: el space-check de sólidos usa el footprint REAL (no la columna),
  y la sonda de protección (`BlockPlaceEvent` sintético) se lanza POR CELDA del caparazón —
  un footprint que invade la región del vecino se rechaza aunque el bloque clicado sí sea tuyo.
- **Guía de testeo**: [`docs/TESTING_MUEBLES.md`](docs/TESTING_MUEBLES.md) — checklist completo
  de la primera prueba en vivo (item MM + furniture.yml de ejemplo + tabla de esperados).
