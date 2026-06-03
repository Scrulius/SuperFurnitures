# SuperBlocksDisplays

A Paper plugin for spawning, animating and managing [block-display.com](https://block-display.com) models on your Minecraft server.

Built for **Paper 1.21.x** and **Java 21+**.

---

## Features

- Spawn any model from block-display.com by its ID
- Permanent model library — download once, spawn whenever you need it
- Animation playback with loop/once modes and adjustable speed (0.25x–4x)
- Model rotation, teleportation, and per-model naming
- Persistent across server restarts
- Full tab completion
- Console and in-game command feedback is silenced automatically

## Installation

Drop `SuperBlocksDisplays-1.0.0.jar` into your server's `plugins/` folder and restart.

## Commands

All commands use `/bde` as the base. Aliases: `/sbd`, `/blockdisplay`.

Where a command accepts `[name]`, you can pass a model name, `nearest`, or omit it to auto-target the closest model within 15 blocks.

### General

| Command | Description |
|---|---|
| `/bde spawn <id\|lib> <name>` | Spawn a model from an API ID or library name |
| `/bde remove [name]` | Remove a model and all its entities |
| `/bde tp [name]` | Teleport to a model |
| `/bde list` | List all active models |
| `/bde info [name]` | Show details about a model |
| `/bde rotate <yaw> [name]` | Set a model's rotation |
| `/bde purge <1-10>` | Kill every display entity within the given radius |

### Animation

| Command | Description |
|---|---|
| `/bde anim play <loop\|once> [name]` | Start animation |
| `/bde anim stop [name]` | Stop animation |
| `/bde speed <0.25-4.0> [name]` | Set playback speed |

### Library

Models saved to the library are stored permanently in `plugins/SuperBlocksDisplays/library/` and are not affected by `/bde clearcache`.

| Command | Description |
|---|---|
| `/bde download <id> <name>` | Download a model and save it to the library |
| `/bde library` | List saved models |
| `/bde undownload <name>` | Delete a model from the library |
| `/bde clearcache` | Clear the temporary API cache |

## Permissions

| Permission | Default |
|---|---|
| `superblocksdisp.use` | OP |

## License

MIT — see [LICENSE](LICENSE).
