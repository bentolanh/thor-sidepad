#!/usr/bin/env python3
"""Lists the gamepads macOS currently believes are attached.

The point of this is duplicates. When an Xbox-compatible pad connects, macOS
mints a synthetic twin so that applications which do not use the GameController
framework can still see it. That twin is normal. What is worth noticing is how
many entries a single pad produces and whether they share a LocationID, because
a LocationID shared with the real device means the same radio is being
published twice rather than once with a synthetic alongside.
"""
import plistlib
import subprocess
import sys
from collections import defaultdict

# Usage page 1 (generic desktop) with usage 4 or 5 is a joystick or a gamepad.
# Usage 0 shows up on entries that have not been fully matched yet and is worth
# seeing, since a half-matched device is exactly the shape of the problem.
WANTED = {4, 5, 0}


def walk(node, out, depth=0):
    if isinstance(node, dict):
        if "VendorID" in node and "ProductID" in node:
            out.append(node)
        for kid in node.get("IORegistryEntryChildren", []) or []:
            walk(kid, out, depth + 1)


def main():
    raw = subprocess.run(
        ["ioreg", "-r", "-c", "IOHIDDevice", "-a", "-l"],
        capture_output=True,
    ).stdout
    if not raw.strip():
        print("no HID devices reported")
        return 0
    roots = plistlib.loads(raw)
    found = []
    for r in roots:
        walk(r, found)

    pads = [
        d for d in found
        if d.get("PrimaryUsagePage") == 1 and d.get("PrimaryUsage") in WANTED
    ]
    if not pads:
        print("no gamepads present")
        return 0

    # One device shows up several times: IOKit publishes the device object, its
    # interface and the event-system shim as separate registry entries, all at the
    # same LocationID. Counting those as separate pads is how an afternoon gets
    # spent hunting a duplicate that was never there. Group by LocationID and
    # report distinct radios, listing the layers underneath.
    by_loc = defaultdict(list)
    for d in pads:
        by_loc[d.get("LocationID", 0)].append(d)

    synthetic = []
    for loc, entries in sorted(by_loc.items()):
        first = entries[0]
        name = str(first.get("Product") or first.get("Manufacturer") or "?")
        ident = f'{first.get("VendorID", 0):04x}:{first.get("ProductID", 0):04x}'
        classes = sorted({e.get("IOObjectClass", "?") for e in entries})
        if any("Synthetic" in c for c in classes):
            synthetic.append((name, ident, classes))
            continue
        print(f"{name}  [{ident}]  location {loc}")
        print(f"    {len(entries)} registry entries: {', '.join(classes)}")

    for name, ident, classes in synthetic:
        print(f"{name}  [{ident}]  <- macOS's own shim ({', '.join(classes)}),")
        print("    minted for an Xbox-identity pad so apps that do not use the")
        print("    GameController framework can still see it. Steam counts it as a pad.")

    real = [loc for loc, e in by_loc.items()
            if not any("Synthetic" in x.get("IOObjectClass", "") for x in e)]
    print()
    print(f"{len(real)} gamepad(s) on their own radio, {len(by_loc) - len(real)} system shim(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
