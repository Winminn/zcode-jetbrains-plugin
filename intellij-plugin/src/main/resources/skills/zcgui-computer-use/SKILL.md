---
name: zcgui-computer-use
description: "Use when a task needs a native desktop app's own UI or the OS on this Windows machine. For anything inside a web page, use Browser Use. Main agent only."
---

# Computer Use (ZC-GUI)

Read or operate the screen of this Windows machine: find apps, take a screenshot,
click / drag / scroll / type at pixels of that screenshot, press keys.

- Prefer a dedicated connector, API, CLI or skill when one can complete the task.
- For browser and web tasks, use Browser Use instead. For files and shell, use
  your regular file tools first.
- Do not use PowerShell SendInput, nirclick, or any other UI-automation trick —
  use only the `mcp__zcgui-computer-use__*` tools listed here.
- Main agent only. Never delegate Computer Use to a subagent.
- This is a coordinate-mode build: there are NO element indices. Everything is
  pixels from a screenshot plus keyboard shortcuts.

## The loop

1. `list_apps` to find the app (exact `name` or `pid`).
2. `get_app_state` with `app_ref` to get a screenshot of the target screen.
3. Find the target on the image, then act on its pixel coordinates.
4. Actions return a fresh screenshot by default (`observe`), so one call does
   the deed AND the re-observe — no extra `get_app_state` needed after
   click/type/key/scroll/drag. The receipt screenshot is taken after a short
   render settle (default 400ms) so menus, windows and animations land in
   frame; for slow transitions (app cold start, long animations) raise
   `observe_delay_ms` (0..5000), or pass 0 to skip the wait.

Success means the tool call was accepted, not that the app acted. Check the
screenshot in the action's response; if you passed `observe: false`, re-observe
with `get_app_state` yourself.

## Window management — prefer `window_manage` over pixel dragging

Moving, resizing, minimizing, restoring or focusing a window is an OS
operation, not a pointing task. NEVER drag window edges with
`left_click_drag` — the resize strip is a few pixels wide and a stale
coordinate drags inside the WRONG app behind it.

```
window_manage {app_ref:{pid}, action:"query"}                 # current rect + display
window_manage {app_ref:{pid}, action:"resize", x:100, y:100, width:800, height:600}
window_manage {app_ref:{pid}, action:"focus"}                 # also restores minimized and re-shows hidden windows
window_manage {app_ref:{pid}, action:"minimize"|"maximize"|"restore"}
window_manage {app_ref:{pid}, window_id:<from list_windows>, action:"move", ...}   # target one specific window
```

`list_windows` returns ALL top-level windows of the process — visible AND
hidden, with class, rect and `window_id`; use `window_id` when one window
matters instead of the process default.

`rect` is in the same logical space as screenshot pixels. `move`/`resize`
need full x, y, width, height — query first, then apply. Mutating actions
attach a result screenshot once the window settles (window animations are
slow); `query` never does.

**When a window refuses your bounds**: `move`/`resize` report `applied:false`
when the app did not take the requested bounds (verified by read-back, so
trust it), and `clamped:true` means the app enforced a larger minimum size
than requested. When you see `applied:false`, do NOT retry the same move —
it will not stick. Durable options: the app's own title-bar buttons
(maximize), OS snap via the `key` tool (`super+Left` / `super+Right` /
`super+Up`), or accept the app's preferred placement. Moving a MINIMIZED
(iconic) window also reports `applied:false` — focus it first (focus
restores minimized windows). Known limit: on
Store/UWP apps (new Notepad, Calculator) the window is hosted by
ApplicationFrameHost; resize works through the host window but may clamp to
the app's own minimum size.

## Coordinates

- `target` / `from_target` / `to` are `[x, y]` pixels **of the latest screenshot
  you received** — `0 <= x < width`, `0 <= y < height` of that image. Submit
  them unchanged.
- Coordinates from `list_apps` titles, memory, or an older screenshot are not
  valid. After any action that may move or resize a window, take a fresh
  observation before clicking again (actions already attach one; `get_app_state`
  otherwise).
- The screenshot covers one whole display (default display 0 = primary). If the
  app is not visible there, try `get_app_state` with `display: 1`, `2`, ...
- `get_app_state` accepts `crop: [x,y,w,h]` (pixels of the latest screenshot)
  to capture and ZOOM a sub-region — use it to identify small UI like tray
  overflow icons, status-bar glyphs, dense toolbars.
- Out-of-range coordinates fail closed with `ELEMENT_UNAVAILABLE` listing the
  valid screen bounds. That is a signal you used a stale or invented pixel —
  re-observe, do not guess.

## Screens and apps

`get_app_state` screenshots the whole display, so any visible app's UI can be
read from it. `list_apps` reports every app that has a window; pass that
`name`/`pid` verbatim as `app_ref` — do not translate or shorten it.

Typical task:

```
list_apps -> find 记事本 (pid 1234)
get_app_state {app_ref:{pid:1234}, display:0} -> screenshot
left_click {target:[x,y]}        (focus the window first)
type {text:"hello"}
key {text:"Return"}
```

Click the target window once first to focus it before typing or keying into it.

## Batching

Keyboard-led sequences (open start menu and search, app hotkey flows) should
be ONE `computer_batch` call instead of N separate `key`/`type` calls:

```
computer_batch {steps:[
  {action:"key",  text:"super"},
  {action:"wait", ms:600},
  {action:"type", text:"workbuddy"},
  {action:"wait", ms:800},
  {action:"key",  text:"Return"}
]}
```

Coordinates inside the batch are pixels of the screenshot taken BEFORE the
batch. It stops at the first failing step, reports `executed` and attaches a
screenshot of where it stopped, so you know the on-screen state without an
extra `get_app_state`. `settle_ms` (default 0) waits after each non-wait step
before the next — use it when a step opens UI that a later step targets
(click button -> dialog appears -> click dialog) instead of inserting explicit
`wait` steps. `observe_final` (default true) attaches one screenshot after all
steps, delayed by `observe_delay_ms` (default 400ms). Max 20 steps / 30s total.

## Tools

Each tool takes one arguments object. Undeclared keys are refused.

```
list_apps({})
list_windows({app_ref})   # ALL top-level windows incl. hidden (window_id, class, rect, visible)
get_app_state({app_ref, display?, include_screenshot?, crop?})   # default display: 0; include_screenshot=false = identity probe, no image
left_click({target:[x,y], mouse_button?="left", click_count?=1, expect_window_pid?, observe?=true, observe_delay_ms?=400})
left_click_drag({from_target:[x,y], to:[x,y], expect_window_pid?, observe?=true, observe_delay_ms?})
scroll({target:[x,y], scroll_direction:"up|down|left|right", scroll_amount?=1, observe?=true, observe_delay_ms?})
type({text, observe?=true, observe_delay_ms?})     # Unicode-safe: clipboard + Ctrl+V into the focused control
key({text, hold_seconds?, observe?=true, observe_delay_ms?})   # "Return", "Tab", "Control_L+a", "super+c", "Up", "F5"
paste({text, format?, observe?=true, observe_delay_ms?})       # same as type; keep for rich content
window_manage({app_ref, window_id?, action, x?, y?, width?, height?, observe?=true on mutating actions, observe_delay_ms?})
computer_batch({steps, settle_ms?, observe_final?=true, observe_delay_ms?})
request_access({capabilities?})   # always ready on Windows
stop_computer_control({reason?})
```

`expect_window_pid` on `left_click` / `left_click_drag`: pass the pid you
believe you are pressing on (from `list_apps`). The server verifies the point
hits that window first and refuses with `GUARD_MISMATCH` otherwise — always
use it near look-alike targets (tray icon clusters, overlapping windows) and
when dragging window edges.

Keyboard: `macOS uses cmd; here (Windows) super/cmd = Win key, ctrl = Ctrl`.
`type` is clipboard-based — focus the right control first (click it), then type.

## Errors

Failed calls return `isError: true` with a JSON text block:

- `code` — `INVALID_APP` (app not running: re-run `list_apps`),
  `ELEMENT_UNAVAILABLE` (stale/off-screen coordinate: re-observe),
  `ACTION_UNAVAILABLE` (unknown key name), `GUARD_MISMATCH` (drag guard hit a
  different window than expected_pid), `TIMEOUT`, `INTERNAL`.
- A failed action may still have reached the app. Never blindly retry a
  non-idempotent action (click/drag/key/type) — re-observe first and decide
  from what you see.

## If you cannot see the screenshot

If a tool result or its surroundings mention `Media omitted ... does not
support image input`, the current model has no vision: you will not be able to
read pixels off screenshots. Do not guess coordinates and do not blind-click.
Tell the user to switch to a vision-capable model in the model picker and retry;
meanwhile `list_apps` / `type` / `key` (keyboard-driven flows) still work.

## Permission prompts

Each computer-use tool call asks the user for permission on first use (the IDE
shows an approval dialog). If the user denies, stop and ask; do not switch to
shell-based automation instead.

## Persistence

Persist until the requested state is visibly present in a fresh screenshot.
If it is unchanged or only intermediate, try another approach — a different
coordinate, the keyboard, or a menu. Respond only when the result is visibly
confirmed, or explain the concrete blocker.
