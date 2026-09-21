# SidePad on the Odin 2 Mini — what macOS hands a game

Recorded 2026-09-21 with PadScope, claiming `045e:02e0`, alongside two 8BitDo controllers
measured the same way in the same session.

| control | SidePad | 8BitDo Ultimate 2 | 8BitDo SN30 Pro |
| --- | --- | --- | --- |
| A | `buttons[0]` | `buttons[0]` | `buttons[0]` |
| B | `buttons[1]` | `buttons[1]` | `buttons[1]` |
| X | `buttons[2]` | `buttons[2]` | `buttons[2]` |
| Y | `buttons[3]` | `buttons[3]` | `buttons[3]` |
| LB | `buttons[4]` | `buttons[4]` | `buttons[4]` |
| RB | `buttons[5]` | `buttons[5]` | `buttons[5]` |
| LT | `buttons[6]` (0.99) | `buttons[6]` | `buttons[6]` |
| RT | `buttons[7]` (0.89) | `buttons[7]` | `buttons[7]` |
| **View/Back** | **nothing** | `buttons[8]` | `buttons[8]` |
| Menu/Start | `buttons[9]` | `buttons[9]` | `buttons[9]` |
| LS click | `buttons[10]` | `buttons[10]` | `buttons[10]` |
| RS click | `buttons[11]` | `buttons[11]` | `buttons[11]` |
| D-pad | `buttons[12..15]` | `buttons[12..15]` | `buttons[12..15]` |
| sticks | `axes[0..3]` | `axes[0..3]` | `axes[0..3]` |

## What this settles

**Our mapping was never shifted.** Every index matches genuine hardware. The shifted buttons
seen in Eastward are Eastward's: a real Xbox Series controller fails there the same way, and
works only with Steam Input, which was confirmed the same night.

**The single real defect was one missing button.** View was written to byte fifteen as AC Back
on the Consumer page, because that is what the model 1708 does and we copied its descriptor
faithfully. macOS does not translate that usage, so the button simply did not exist. It is an
ordinary button at bit ten now, below Menu at eleven, which is where the Xbox layout puts it.

**Identity is not the cause of anything here.** The SN30 Pro carries `045e:02e0`, exactly as we
do, and maps perfectly. Any plan to change identity must be justified by the WindowServer
livelock alone, which is a separate fault with separate evidence.

## Still to verify

Re-run this walk after the View fix and confirm `buttons[8]` arrives. That is the whole test.
