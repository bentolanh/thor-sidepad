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

---

## Identities measured on this Mac, 2026-09-21

Read from `ioreg` while each was connected. The version is not decoration: a game keying its
controller database on the SDL GUID uses vendor, product **and** version, and being wrong by two
bytes has already cost this project a mapping once.

| device | identity | version | transport |
| --- | --- | --- | --- |
| Xbox Wireless Controller (Series X\|S) | `045e:0b13` | `0x0520` (1312) | Bluetooth LE |
| 8BitDo Ultimate 2 Wireless | `2dc8:6012` | `0x0001` | Bluetooth LE |
| SidePad (what we claim) | `045e:02e0` | `0x0903` (2307) | Bluetooth LE |

`045e:02e0` is the Xbox Wireless Controller **model 1708**, the Xbox One S generation — not the
Series controller. Eastward and Steam map the Series pad correctly and read ours positionally,
which is consistent with Steam holding a profile for `0b13` and none for what we send.

The Series controller's own report descriptor, 283 bytes, was dumped on 2026-09-21 and its
report 1 is byte-for-byte identical to ours in every field: same axes, same 10-bit triggers,
same hat, same Buttons 1..15 at byte 13. It differs only at byte 15, where it carries its Record
button and we carry padding.

---

## How macOS routes a pad, by identity — measured 2026-09-21

Read from `ioreg` with each identity paired in turn over Bluetooth Low Energy. The pad sent the
**same report and the same descriptor** every time; only the vendor and product numbers changed.

| identity | service class macOS assigns | synthetic shim | is it a controller? |
| --- | --- | --- | --- |
| `1209:5350` (ours, pid.codes pool) | `AppleUserHIDEventService` | none | **no** |
| `045e:02e0` (Xbox model 1708) | `IOHIDEventDummyService` | yes | yes, on the Xbox path |
| `2dc8:6012` (8BitDo Ultimate 2) | `AppleGCHIDEventDummyService` | yes | yes, and works natively |
| `045e:0b13` (real Xbox Series) | `IOHIDEventDummyService`, plus its own `045e:0b12` backed by `AppleUserHIDDevice` | yes | yes, needs Steam Input in Eastward |

**Apple works from a list of known controllers.** An unrecognised vendor gets plain HID and is
not a game controller at all — tested directly, and the pad stopped being one. So claiming
Microsoft's numbers is not what puts us on a bad path; it is what gets us recognised.

**But the two recognised paths are not equal.** The 8BitDo is handed to Apple's GameController
HID service and works natively in everything tried. Xbox-identity devices are handed to the
generic service with a driver-backed device alongside, and in Eastward a genuine Xbox controller
needs Steam Input while this pad is read positionally.

**So the theory that dies here** is that our own identity would free us. It does the opposite.
What remains open is whether an identity on the 8BitDo's path would work better than the Xbox
one — which cannot be tested without claiming an 8BitDo's numbers, and would need their report
layout, which does not match their own published descriptor.
