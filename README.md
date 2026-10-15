# VRAM Steady

A small Java mod for **Project Zomboid Build 42** that stops video memory (VRAM) from slowly filling up and then stuttering.

Needs [ZombieBuddy](https://steamcommunity.com/workshop/browse/?appid=108600&searchtext=ZombieBuddy) (loads Java mods). Client side only. License: MIT.

## The problem

In stock B42 the renderer uploads almost every small draw batch (lines, shapes, much of the UI, many mods' drawing) with
`glBufferData(..., GL_STREAM_DRAW)`. Every flush re-creates the buffer storage at the exact size of that batch.
In a busy scene that is tens of thousands of times per second (measured here: about 33,000/s). The graphics driver only
returns those discarded blocks in bulk, so VRAM climbs by roughly 1 GB per minute, textures get pushed out (stutter),
and every few minutes it all drops at once (hitch). Cards with little VRAM suffer most.

## What the mod does

Same data, same draw calls. Only the upload changes: each buffer gets a storage size from a few fixed size classes
(64 KB, 128 KB, ... powers of two) and every batch is written with `glBufferSubData` after the old contents are
released with the standard "orphan" call. The driver can recycle same-size blocks instead of piling them up.
No gameplay change.

## Measured

One test machine (RTX 4060 Ti, 8 GB), same scene:

| | VRAM swing |
|---|---|
| stock B42 | about 1.5 GB |
| with VRAM Steady | about 0.1 GB (flat) |

No FPS loss seen in testing. This is one machine; please report your numbers.
Tested with vanilla, Viewpoint and PZ Optimization. Not tested with other optimization mods.

## Install

1. Install ZombieBuddy (steps are on its Workshop page).
2. Get VRAM Steady from the Steam Workshop, or copy `release/VRAMSteady.jar` into a mod folder as
   `VRAMSteady/42/media/java/client/VRAMSteady.jar` (plus a `mod.info` that requires ZombieBuddy).
3. Enable ZombieBuddy and VRAM Steady in the Mods menu and start the game.
4. ZombieBuddy asks once whether to allow the Java mod. Click **Allow**.
5. Check `Zomboid/console.txt` for the line `[VRAMSteady] mode orphan`.

Multiplayer: the server needs the mod (and ZombieBuddy) in its mod list.

## Settings (optional)

`Zomboid/vramsteady.properties` is created on first start.

- `mode=orphan` (default), `subdata` (no new storage at all) or `off`
- `minKb` smallest storage size class in KB (default 64)
- `overlay=on|off` small status line in the bottom-left corner, `Ctrl+Shift+V` toggles it in game

## Source

| File | Role |
|---|---|
| `src/vramsteady/Steady.java` | the buffer logic, settings |
| `src/vramsteady/PatchBufferData.java` | ZombieBuddy patch on `GLVertexBufferObject.bufferData` |
| `src/vramsteady/PatchDestroy.java` | forgets a buffer's size class when it is destroyed |
| `src/vramsteady/PatchUIRender.java`, `Overlay.java` | optional status line |
| `src/vramsteady/Main.java` | ZombieBuddy entry point |

`release/VRAMSteady.jar` is the compiled v1.1 (Java 21 class files). It is built against the ZombieBuddy API and the
game's classes, which are not included here.

## Honest notes

- Built with AI help (Claude) and checked by measurement on the author's PC; the code is small (under 300 lines) and
  meant to be read.
- It relies on internal game classes (`zombie.core.VBO.GLVertexBufferObject`). A game update that renames them makes
  the mod switch itself off (`inactive`, stock upload) instead of crashing.
- Issues and pull requests are welcome, especially measurements from other GPUs.
