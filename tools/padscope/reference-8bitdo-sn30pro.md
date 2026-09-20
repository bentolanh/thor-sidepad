# 8BitDo SN30 Pro (XInput mode) — what macOS hands a game

Recorded 2026-09-21 with PadScope on macOS 26. Device: **`045e:02e0`** — Microsoft's identity,
the exact one this pad claims — `mapping="standard"`, 17 buttons, 4 axes.

| control | index | control | index |
| --- | --- | --- | --- |
| A | `buttons[0]` | LS click | `buttons[10]` |
| B | `buttons[1]` | RS click | `buttons[11]` |
| X | `buttons[2]` | D-up | `buttons[12]` |
| Y | `buttons[3]` | D-down | `buttons[13]` |
| LB | `buttons[4]` | D-left | `buttons[14]` |
| RB | `buttons[5]` | D-right | `buttons[15]` |
| LT | `buttons[6]` | | |
| RT | `buttons[7]` | | |
| View/Back | `buttons[8]` | | |
| Menu/Start | `buttons[9]` | | |

Left stick `axes[0]`/`axes[1]`, right stick `axes[2]`/`axes[3]`, right and down both positive.

Identical to the Ultimate 2 Wireless in every index, despite carrying a completely different
vendor and product.

## Why this one matters more than the other

It claims `045E:02E0`. So does SidePad. And it is translated perfectly.

**Therefore the Xbox identity is not what shifts our buttons.** That theory is dead, and so is
the plan to fix the mapping by changing identity — nothing about `045E:02E0` prevents macOS
from producing a correct standard mapping. Whatever we do differently is in the report we send
or the descriptor we advertise, not in who we say we are.

The identity question stays alive for the *other* problem — the WindowServer livelock — but
that is a separate fault with separate evidence, and the two must not be conflated again.

## The measurement that comes next

Run the same walk with SidePad attached. Both references above are the same table, so any
disagreement is ours and is the shift itself, located to a specific index.

Untested and worth knowing: whether the SN30 Pro also triggers the livelock. If it does, the
trigger is the identity. If it does not, the identity is innocent there too and something
narrower is to blame.
