"""Names the gamepad-shaped HID entries macOS holds, ignoring the Mac's own SPU device.

gccpu.sh counts '"PrimaryUsage" = 5' and calls the number "hid pads". Two of those are
always present on this machine and are not pads at all — they are AppleSPUHIDDevice
entries that happen to declare the gamepad usage. So its "4 pads" means two real ones.
"""
import sys, re
n = 0
for b in sys.stdin.read().split("+-o "):
    if '"PrimaryUsage" = 5' not in b or "AppleSPUHIDDevice" in b:
        continue
    n += 1
    def f(k):
        m = re.search(r'"%s" = "?([^"\n]+)' % k, b)
        return m.group(1).strip() if m else "-"
    print("   %-30s vid=%-6s pid=%-6s transport=%s" % (f("Product")[:30], f("VendorID"), f("ProductID"), f("Transport")))
print("   (real pad entries: %d)" % n)
