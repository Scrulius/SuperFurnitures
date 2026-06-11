# Guía de testeo — SuperFurnitures (muebles para jugadores)

Checklist para la primera prueba en vivo del módulo de muebles. Server de pruebas:
`C:\Users\Knopp\Desktop\server paper testeos\` (jar ya desplegado; **reinicio completo**, no hot-swap).

## 0. Preparación (una vez)

1. **Modelo**: descarga uno de block-display.com si no tienes:
   ```
   /bde library            ← ver los que ya hay
   /bde download <id> Silla
   ```
   Una silla/banco/sofá es lo ideal (tiene asiento que probar).

2. **Item de MythicMobs** (`plugins/MythicMobs/Items/` o vía `/mm items`):
   ```yaml
   SillaItem:
     Id: PLAYER_HEAD
     Display: '&6Silla de madera'
     Options:
       Color: ""
   ```
   Sirve cualquier item MM (lo recomendado: player head con textura base64 para que el
   item "parezca" el mueble). No necesita `Placeable` — el plugin intercepta el clic.

3. **Entrada en `plugins/SuperFurnitures/furniture.yml`**:
   ```yaml
   furniture:
     silla:
       model: Silla
       mythic-item: SillaItem
       anchor: floor
       solid: true
       hitbox: { width: 0.8, height: 1.0 }
       interaction:
         type: seat
         seats: [ "0.0,0.4,0.0" ]
   ```
   Después `/sf reload` (debe decir "1 mueble(s)"). Si dice 0, el error sale en consola
   (modelo inexistente en la library, item duplicado…).

## 1. Ciclo básico de jugador

| Paso | Esperado |
|---|---|
| `/sf give silla` y clic derecho en el suelo | El mueble aparece mirando hacia ti (snap 90°), sonido de colocar, actionbar verde. El item se consume (en survival). |
| Clic derecho al mueble | Te sientas (asiento 0.4 sobre el suelo). |
| Bajarte (sneak) | Sales SIN asfixiarte aunque el mueble sea sólido. |
| Agachado + golpe | El mueble gira 90° (sonido). Las barreras se recolocan. |
| Agachado + clic derecho mano vacía | Lo recoges: vuelve el item, las barreras desaparecen. |
| Golpe sin agacharte | No le pasa nada + hint en el actionbar. |
| TNT / pistón / fuego contra él | Indestructible (las barreras son barrier blocks + entities invulnerables). |

## 2. Herramientas de tuning (admin, mirando el mueble a <5 bloques)

- `/sf show` — wireframe 10s: hitbox cian + barreras rojas (solo lo ves tú).
- `/sf info` — dueño, piezas, barreras, hitbox, interacción.
- `/sf hitbox 1.2 1.5` — el hitbox cambia al instante (re-lanza el show solo).
- `/sf seats` — aparecen **maniquís sentados** en cada plaza:
  - ponte de pie donde quieras otra plaza **mirando hacia donde miraría el jugador** → `/sf seats add`
  - `/sf seats move 2 y -0.2` (ejes locales x/y/z + `yaw` en grados)
  - `/sf seats save` → escribe furniture.yml y aplica YA a todos los colocados.
- `/sf footprint` — la huella sólida se ilumina en naranja:
  - **golpea bloques** para añadir/quitar celdas (las barreras existentes también se golpean)
  - `/sf footprint add` = celda bajo tus pies (para huecos en el aire)
  - `/sf footprint save` → re-aplica el caparazón de ESTE mueble en el acto.

## 3. Robustez / admin

- `/sf list` (todos por dueño) y `/sf list <jugador>`; `/furniture list|limit` como jugador.
- `/sf purge <jugador>` — borra todos sus muebles (carga chunks si hace falta).
- `/bde purge 10` cerca de un mueble — NO debe borrarlo (solo modelos admin y restos huérfanos).
- `kill @e[type=interaction]` a mano + recargar el chunk (alejarse y volver) → la entrada
  huérfana se poda sola del índice (log `[Muebles] Entrada huérfana podada...`).
- Límites: `limits.per-chunk: 2` + colocar 3 en el mismo chunk → el 3º se rechaza.
- Mundo en `disabled-worlds` → no deja colocar.
- Colocar con el footprint invadiendo región WorldGuard ajena → rechazado
  ("invade una zona donde no puedes construir").

## 3b. Novedades v1.4.0 (items nativos + admin)

- **Item nativo**: añade a un mueble la sección `item:` (material PLAYER_HEAD + head-texture
  base64 + name/lore) y `/sf reload` → `/sf give` da el item del plugin (con su nombre/skin);
  colocar y recoger devuelve EL MISMO item. Con `mythic-item` quitado del yml debe seguir todo
  funcionando aunque MythicMobs no esté.
- Item nativo de un tipo BORRADO del catálogo → clic derecho NO coloca nada ni actúa como
  cabeza vanilla (actionbar "ya no está en el catálogo").
- `/sf reload` con un error a posta en furniture.yml (p.ej. material inválido) → el error sale
  EN EL CHAT, no solo en consola.
- `/sf check` → catálogo e índice en verde; rompe algo (menu sin nombre, footprint con
  solid:false) y debe salir como ⚠.
- `/sf gui` → GUI con TODOS los muebles del server (lore con dueño); clic = teleport al mueble,
  shift+clic = va a tu inventario. `/sf gui <jugador>` filtra.
- `/sf give silla TuNick 16` → da 16 de golpe (stackean si el item es idéntico).
- Al colocar, el actionbar dice "Mueble colocado. (n/límite)".

## 3c. Novedades v1.5.0 (QoL elegida)

- **Rotación 45°**: silla (no sólida) + agachado+golpe → gira de 45 en 45 (actionbar dice los
  grados); una mesa `solid: true` sigue de 90 en 90.
- **Plaza más cercana**: sofá de 2+ plazas — ponte junto a la plaza derecha y clica: te sienta
  AHÍ, no en la #1.
- **¿De quién es?**: clic derecho en un mueble decorativo (interaction none) ajeno → actionbar
  "Mueble — de Fulano".
- **Mueble de servidor**: `/sf place silla` mirando al suelo → se coloca sin consumir item; otro
  jugador puede SENTARSE pero NO recogerlo ni girarlo; tú (con bypass) sí. `/sf purge server`
  los quita todos. En `/sf gui` sale con dueño "Servidor".
- **Límite por tipo**: pon `max-per-player: 1` a la silla + `/sf reload` → la 2ª silla se
  rechaza ("Ya tienes el máximo de este mueble") aunque el límite global no esté lleno.
- **/sf near**: lista los muebles a ≤20 bloques con dueño y distancia; CLIC en una línea = tp.
- **/sf audit**: coloca/recoge/gira algo y mira `/sf audit` (y `/sf audit TuNick 5`) — debe
  listar las operaciones con fecha/lugar; el archivo es `plugins/SuperFurnitures/furniture-log.jsonl`.
- **Arranque**: en el log de arranque sale "Muebles: N tipo(s) en catálogo, M colocado(s)..."
  (+ WARNs si hay errores de config/huérfanos).

## 4. Animados (si el modelo trae animación)

`animated: true` en el yml → el mueble se anima en loop al colocarlo, sigue tras
recargar el chunk y para/arranca al descargar/cargar. ⚠️ Punto sin verificar a ojo:
la matriz JOML transpuesta — si el modelo sale DEFORMADO, es eso (ver CLAUDE.md).

## Síntomas conocidos

- "El item no coloca nada" → el item no es de MythicMobs o `mythic-item` no coincide
  (`/sf types` muestra el binding) o MythicMobs no estaba cargado al arrancar.
- "0 muebles registrados" → el `model` no existe en la library (`/bde library`).
- Maniquí del editor con skin por defecto → normal (es la entity mannequin vanilla).
- Los muebles ya colocados no cambian su caparazón tras editar el footprint → esperado,
  se hornea al colocar; recolócalos (el editado sí se re-aplica al guardar).
