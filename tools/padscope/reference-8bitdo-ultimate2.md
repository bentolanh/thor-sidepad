# 8BitDo Ultimate 2 Wireless — what macOS hands a game

Recorded 2026-09-21 with PadScope, over Bluetooth Low Energy, on macOS 26.
Device: `2dc8:6012`, version `0x0001`, `mapping="standard"`, 17 buttons, 4 axes.

This matters because it is the pad that does **not** trigger the WindowServer/gamecontrollerd
livelock and does **not** start the haptics run loop — it declares its own identity rather than
Microsoft's, and macOS still translates it perfectly. It is therefore the thing to imitate.

| control | index | control | index |
| --- | --- | --- | --- |
| A | `buttons[0]` | LS click | `buttons[10]` |
| B | `buttons[1]` | RS click | `buttons[11]` |
| X | `buttons[2]` | D-up | `buttons[12]` |
| Y | `buttons[3]` | D-down | `buttons[13]` |
| LB | `buttons[4]` | D-left | `buttons[14]` |
| RB | `buttons[5]` | D-right | `buttons[15]` |
| LT | `buttons[6]` | Guide | `buttons[16]` |
| RT | `buttons[7]` | | |
| View/Back | `buttons[8]` | | |
| Menu/Start | `buttons[9]` | | |

| stick | axis | direction recorded |
| --- | --- | --- |
| left | `axes[0]` / `axes[1]` | right = +1.00, down = +0.87 |
| right | `axes[2]` / `axes[3]` | right = +1.00, down = +0.96 |

That is the W3C Standard Gamepad layout exactly, with no index out of place. Nothing clever is
happening: macOS does the whole translation, and a game reading the standard mapping gets a
correct controller without the pad ever claiming to be an Xbox.

## What to do with it

Run the same walk with the SidePad attached and compare. Same table means our translation is
already correct and the shifted buttons come from somewhere else. A different table — X landing
on `buttons[3]`, say — is the shift itself, measured.

## A caution about the raw side

The raw HID bytes are NOT the report this device's descriptor advertises. The descriptor decodes
as buttons at byte 7; the live report puts them around byte 14, and seven bytes stream motion
data continuously. Do not copy a raw layout from the descriptor alone — it does not describe
what the device actually sends.
