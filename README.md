# Thor SidePad

Play one-handed on the AYN Thor. SidePad puts controller buttons on the bottom screen, and
the game on the top screen reads them as presses on the Thor's own controller.

<p align="center"><img src="docs/pad.png" width="420" alt="The pad on the Thor's bottom screen: A/B/X/Y, a right stick, R1/R2, Start, M1/M2 and the shield button, over a dark backdrop"></p>

## What it is for

- **One-handed play.** Holding a drink, or just resting the other arm. The buttons your free
  hand would press sit under the thumb you still have on the device.
- **Any button, anywhere.** Put A/B/X/Y, shoulders, triggers, Start, Select, a d-pad or a
  virtual stick wherever your thumb lands. Drag, resize, save it as a preset.
- **Extra buttons the Thor does not have.** M1 and M2 appear to games as two more buttons,
  handy for quick-save, fast-forward or menu shortcuts in emulators.
- **Bluetooth controllers too.** Presses can go into a connected Bluetooth pad instead of the
  built-in one, for the same trick on any controller Android sees.

## What you need

- An AYN Thor. Other dual-screen Android 11+ handhelds should work, with the notes at the end.
- [Shizuku](https://shizuku.rikka.app/), a free app that gives SidePad the access it needs.
  Android does not let one app press buttons for another; Shizuku lends SidePad the same
  access a computer has over USB debugging. SidePad walks you through installing and
  starting it.

## Install

1. Download the latest APK from the Releases page and open it on the Thor. Allow installs
   from your browser or file manager if asked. Obtainium can track releases here for updates.
2. Open Thor SidePad. The setup screen is a short checklist and each step unlocks the next:
   Shizuku, draw over other apps, notifications (optional), Start.
3. Press Start. A short guide appears on the bottom screen and teaches the two gestures by
   having you do them.

## Everyday use

Everything happens on the bottom screen.

- **Pull up from the bottom edge** to show the pad. Pull up again to hide it.
- **Pull down from the top edge** to open the SidePad panel: choose which controller receives
  the presses, show or hide the pad, edit the layout, turn the shield on, pick a backdrop,
  set button transparency, stop SidePad.
- **The shield.** When it is on, nothing behind the pad can be touched by accident, and your
  thumb can slide from one button to the next. When it is off, only the buttons are covered
  and the app under the pad stays usable. There is a shield button on the pad itself.
- **Playing in a dark room.** With the shield on, choose a backdrop: Dim, Dark for a plain
  black bottom screen, or Frosted to blur whatever is behind the pad. The transparency slider
  makes the buttons more see-through.

  <img src="docs/pad-frosted.png" width="300" alt="The same pad over the frosted backdrop">
- **Layouts and profiles.** Edit layout opens the editor on the bottom screen: tap a button to
  select it, drag to move, pinch to resize, Add for more buttons or a virtual stick, Delete to
  remove. The chip under the toolbar names the profile you are editing and opens the profile
  picker, where each profile offers Open, to edit that one, and Overwrite, to store what is on
  screen into it; the picker also makes a new blank profile. Deleting a profile lives in the
  control panel's profile list instead, away from the editing controls. Save writes into the
  profile you are on. Exit leaves, asking first whether to save if there is unsaved work, and
  the back gesture does the same. Switching profile with unsaved work asks too, so nothing is
  lost silently.
- **Device controls on the pad.** Add has pages for Controller, Macro and System, grouped by
  what the thing affects: Controller goes to the game, Macro changes how the pad's own buttons
  behave, System acts on the device and its screens.
  The D-pad is one cross you press in any of eight directions.
  Sliders set brightness and volume for each screen, plus one brightness slider that moves
  both screens together; Screen holds Home and Back for either screen and the shield toggle.
  So the things you would reach for the Thor's own keys or the Control Center for are one tap
  from the game.
  Three profiles come built in: Left hand, Right hand and Face buttons. A built-in cannot be
  written over, so saving while on one asks for a name and keeps your copy instead.
- **How a button presses.** Select a button in the editor and tap Behavior: Sticky means a
  tap holds it down and the next tap lets go, Turbo means it presses itself about twelve times
  a second for as long as you hold it, at a rate you pick (Slow, Medium, Fast, or a slider for
  anything between), and the two together mean a tap starts it repeating
  until you tap again. A turbo button shows two chevrons. The Macro page also has HOLD and
  TURBO elements: tap one, then tap any button, and that button holds or repeats hands-free
  until you tap it again.
- **Simple media controller.** System holds a media unit: one bar with previous, play or pause,
  and next, plus a volume track. It reaches whatever is playing, whichever app that is, and the
  track moves that app's own audio: the Thor scales the second screen's sound separately, so the
  track follows the screen the player is on rather than always moving the main one.
- **Video controller.** System also has a video unit for players like YouTube and Netflix. A band
  at the top carries the app's icon and what is playing, the video or track title rather than just
  the app's name, and brings that app forward on the screen it was already on.
  Below it a timeline shows how far in you are and drags to seek, then the full transport with
  jump back and forward ten seconds either side of play or pause, then a volume row marked with a
  speaker. The play mark shows what it will do, since it knows whether the player is running. Both
  tracks answer a drag and ignore a tap, so a stray touch cannot jump the video or the volume. It
  needs nothing beyond Shizuku. Or add a HOLD button to the pad: tap HOLD, then any
  button, and that button stays down until you tap it again. A held button shows a yellow
  dot. Hiding or stopping the pad releases everything.
- **Glyphs per preset.** The editor's Glyphs button labels the buttons the Xbox, PlayStation
  or Nintendo way, including the shapes those pads print for Select and Start rather than
  words. Only the labels change; A is still A to the game.
- **Keep your presets.** The app screen has Export and Import under Presets. Export writes
  your presets and the current layout to a file you choose; Import reads one back. Do this
  before uninstalling, and to carry layouts to another device.
- **Where presses go.** The panel's Controller page lists the Thor's own controller, any
  Bluetooth pad that is connected, and a separate virtual pad that games see as a second
  player.
- **Quick Settings tile** and the notification also show or hide the pad.

## Good to know

- Shizuku stops when the Thor reboots. In the Shizuku app, turn on "Start on boot (wireless
  debugging)" so it comes back on its own; otherwise start it again from the Shizuku app.
  SidePad tells you when Shizuku is not running and opens it for you.
- Switching the Thor's controller style (Standard, Xbox) while playing is fine; SidePad
  follows the change on its own.
- SidePad never reads your controller's buttons, so it does not interfere with key mappers.
- The app icon can be switched between a Famicom and a Game Boy look in the settings.

## Pairing with a computer, console or phone

The pad is a Bluetooth Low Energy gamepad, so anything that accepts one should accept this. It
has been tried on macOS, iOS, iPadOS, Android, SteamOS and Windows 11. Four take it as it
comes; two hide it until a setting is changed.

Whichever you are pairing from, **the pad is only on the air while it is findable**. Make it
findable from the panel and scan during that window. Outside it the pad is silent on purpose,
and a host scanning then hears nothing at all — which looks exactly like a device that does not
work, and was the first wrong answer on three of these platforms.

**macOS, iOS, iPadOS and Android** need nothing special. Pair from the machine while the pad
is findable. On an iPhone the pad also carries through Steam Link into a streamed session, which
is a harder test than a local one: the buttons have to survive being handed to another machine's
game.

**SteamOS** hides it by default. In Game Mode: Settings → Bluetooth, turn on **Show all
devices**. The pad then appears and pairs from that screen, and Steam reads its battery level,
so it sits in the list like any other controller.

**Windows 11** hides it the same way. Settings → Bluetooth & devices → Devices → **Bluetooth
devices discovery**, and change **Default** to **Advanced**. Microsoft describes the switch in
its own words: Default connects common accessories, Advanced shows all types of device.
Once paired it arrives as a "HID-compliant game controller" with the standard mapping and a
working vibration motor, which is how Windows treats any Low Energy pad: it does not go through
the Xbox driver, because that one binds over USB and Bluetooth Classic rather than Low Energy.
Anything reading controllers through SDL or Steam Input sees it. Whether a game that insists on
XInput does has not been tried.

Both hide it for the same reason, and it is not something this app can fix. A Low Energy device
says what kind of thing it is through a GAP value called Appearance, and Android gives an app no
way to set one: an advertisement can carry a name, service UUIDs, service data and manufacturer
data, and that is the whole list — while the GAP service that holds Appearance belongs to the
Bluetooth stack rather than to us. A pairing list that sorts devices by Appearance therefore has
nothing to sort this one by, and a list that hides what it cannot sort hides this. Apple's stack
looks for the gamepad service itself and never filters on Appearance, which is why Macs and
iPads find the pad without being asked twice.

### What to appear as

Two choices on the pairing screen, and they are not two versions of the same thing.

**Xbox Wireless Controller** is the one to play with. It behaves as a real Xbox controller does,
which is what nearly every third-party controller claims to be: games that work with one work
with this, and games that want Steam Input want it either way. It is the only controller SidePad
offers at the moment; a PlayStation or Nintendo one would sit beside it.

It works because its report map is copied byte for byte off real hardware, and that copy is
exactly what earns it native treatment on all six platforms above. Nothing can be added to it
without ending that — which is why the trackpad, and the keyboard and media keys when they
arrive, cannot live there.

**SidePad** is where those live instead. It is honest about what it is, and a machine has to be
taught which button is which. As a game controller it is the weaker choice — macOS will not treat
it as one at all — so it is not the way to play.

Either is a different device as far as the machine is concerned, so changing this means pairing
again. The radio changes with it where it has to: the Xbox pad speaks Low Energy, as this one
does, while a PlayStation or Nintendo profile would be Bluetooth Classic, because that is what
those controllers are. Nothing asks which radio to use — choosing what to appear as chooses it.

One last thing that looks wrong everywhere and only matters in one place: no host works out what
the pad actually is, and no two guess alike. macOS, Android and SteamOS file it as a phone,
because the handheld's ordinary Bluetooth radio says it is one; Windows shows it as a desktop
computer instead. That is the Appearance problem again, seen from the other end — with nothing
to sort the pad by, each stack falls back on its own default.

On three of them the wrong icon is all it is. On macOS the label is acted on, and it is why a
dropped connection there has to be paired again instead of coming back on its own.

## Spare "Odin2Mini" rows in a Mac's Bluetooth list

Pairing the pad more than once leaves extra rows behind, all with the pad's name. They are
**cosmetic**. Nothing is wrong, nothing is using them, and a restart clears them on its own —
leave them alone if they do not bother you.

They appear because a Low Energy peripheral on Android advertises under a random address that
changes, so a Mac files each pairing separately rather than recognising it as the same pad. A
controller with fixed firmware never does this, which is why an 8BitDo leaves one row forever.

Two places show them, and each is cleared differently:

**The Bluetooth pane in System Settings** — turn Bluetooth off and on. The strays under Nearby
Devices are connections the Mac never saw closed, usually because the app was stopped or
reinstalled mid-link, and restarting the radio drops them.

**The Bluetooth menu in the menu bar** — that list is Control Center's own cache and a radio
restart does not touch it. Restart Control Center instead:

```bash
killall ControlCenter
```

It comes straight back, and the list is rebuilt from what the Mac actually knows.

One thing that looks wrong and is not: the pad shows up twice on a Mac. An entry with a phone
icon in My Devices, which stays "Not Connected" and is never used, and the gamepad itself, which
lives in the menu and under Nearby Devices. Android hands out both identities and a Mac files
them separately; the gamepad is the one doing the work.

## Other devices

SidePad was built and tested on the AYN Thor. On another Android 11+ device with a second
touch screen, expect the following:

- Sending presses into a real controller works wherever Shizuku runs.
- The virtual second-player pad needs the device to allow Shizuku access to uinput; many do
  not, in which case only real controllers are offered.
- M1 and M2 depend on the controller advertising two spare buttons; if it does not, they show
  as disabled on the pad.
- The frosted backdrop needs a device that can blur behind windows; otherwise it falls back
  to a heavy dim.
- Keeping the handheld awake while you play works anywhere Shizuku does. It reports each press
  to Android as the activity it is, through a call the shell user is allowed to make on any
  Android build — nothing about it is particular to these handhelds.
- Whether a dark screen stops the controller, though, is the handheld's own business. The Thor
  and the Odin 2 Mini both build their gamepad in software and it stops when their display does,
  so on those two the screen has to stay on while you play. A device with a real controller node
  may well keep working with the screen off.

## Built with Claude Code

This app is developed using Claude Code.

## License

MIT. See `LICENSE`. Developer notes are in `DEVELOPMENT.md`.

## Watching macOS's controller daemon

`tools/gcwatch/` holds three recorders for the times `gamecontrollerd` misbehaves — what it
costs, whether anything is flooding its driver endpoint, and what was running when. Copy them
to `~/Library/Application Support/thor-sidepad/gcwatch/` and run them from there. Run
`driverflood.sh --selftest` before trusting a quiet log.

What they have already told us, and what to do next time, is in the vault note
"gamecontrollerd overload — what we know" under `Claude/thor-sidepad/`.
