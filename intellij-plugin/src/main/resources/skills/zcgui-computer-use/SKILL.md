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
4. Re-observe with `get_app_state` before deciding the next step.

Success means the tool call was accepted, not that the app acted. Always
re-observe to confirm the result landed (a click on the wrong spot usually
leaves the screen unchanged — repeating it will not help; re-check the
coordinates against the LATEST screenshot instead).

## Coordinates

- `target` / `from_target` / `to` are `[x, y]` pixels **of the latest screenshot
  you received** — `0 <= x < width`, `0 <= y < height` of that image. Submit
  them unchanged.
- Coordinates from `list_apps` titles, memory, or an older screenshot are not
  valid. After any action that may move or resize a window, take a fresh
  `get_app_state` before clicking again.
- The screenshot covers one whole display (default display 0 = primary). If the
  app is not visible there, try `get_app_state` with `display: 1`, `2`, ...
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
get_app_state {app_ref:{pid:1234}} -> verify
```

Click the target window once first to focus it before typing or keying into it.

## Tools

Each tool takes one arguments object. Undeclared keys are refused.

```
list_apps({})
list_windows({app_ref})
get_app_state({app_ref, display?, include_screenshot?})   # default display: 0
left_click({target:[x,y], mouse_button?="left", click_count?=1})   # click_count 2 = double
left_click_drag({from_target:[x,y], to:[x,y]})
scroll({target:[x,y], scroll_direction:"up|down|left|right", scroll_amount?=1})
type({text})            # Unicode-safe: clipboard + Ctrl+V into the focused control
key({text, hold_seconds?})   # "Return", "Tab", "Control_L+a", "super+c", "Up", "F5"
paste({text, format?})       # same as type; keep for rich content
request_access({capabilities?})   # always ready on Windows
stop_computer_control({reason?})
```

Keyboard: `macOS uses cmd; here (Windows) super/cmd = Win key, ctrl = Ctrl`.
`type` is clipboard-based — focus the right control first (click it), then type.

## Errors

Failed calls return `isError: true` with a JSON text block:

- `code` — `INVALID_APP` (app not running: re-run `list_apps`),
  `ELEMENT_UNAVAILABLE` (stale/off-screen coordinate: re-observe),
  `ACTION_UNAVAILABLE` (unknown key name), `TIMEOUT`, `INTERNAL`.
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
