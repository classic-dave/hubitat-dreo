# Dreo integration for Hubitat

Control your Dreo fans, purifiers and humidifiers from Hubitat. One app signs
into your Dreo account and finds your devices; each device then appears in
Hubitat like any other, ready for dashboards, rules and voice assistants.

> [!NOTE]
> **Not everything here has run on real hardware.** Support for anything other
> than tower fans was written by studying Dreo's official Home Assistant
> integration together with real device data from Hubitat logs, then confirmed
> by testers where possible. The status column below says which is which.
>
> Reports are very welcome, especially on the untested types. Open an issue or
> reply in the Hubitat community thread.

> Unofficial and community-maintained. Not affiliated with or endorsed by Dreo.
> Ported from the official Dreo Home Assistant integration
> ([dreo-team/hass-dreoverse](https://github.com/dreo-team/hass-dreoverse), MIT).

## Will my device work?

There is no built-in list of models. When you sign in, Dreo's servers tell
Hubitat what each of your devices can do, and the driver adjusts itself to match.
So if your device's *type* is in this table, your particular model will most
likely work even if it is newer than anything listed.

| Driver | Device type | Status |
| --- | --- | --- |
| Dreo Basic Device | Air conditioners (`DR-HAC`), heaters | On/off and mode only, untested |
| Dreo Fan | Air circulators (`DR-HAF`, `DR-HPF`) | Working, confirmed by a tester |
| Dreo Fan | Air purifiers (`DR-HAP`) | Working, confirmed by a tester |
| Dreo Fan + Dreo Fan Light | Ceiling fans (`DR-HCF`) | Written, nobody has tested it |
| Dreo Fan | Evaporative coolers (`DR-HEC`) | Fan side only, untested |
| Dreo Fan | Tower fans (`DR-HTF`) | Working |
| Dreo Humidifier | Humidifiers (`DR-HHM`) | Working, confirmed by a tester |

Air purifiers use the fan driver because Dreo's own system treats a purifier as
a fan with a filter attached. Same for evaporative coolers, which are fans with
a water tank.

Dehumidifiers are not supported yet and will show up as a basic on/off device.

### What you get

**Fans, circulators and purifiers.** On/off, speed (both named speeds like
"medium" and exact numbers), oscillation, and the preset modes your model
offers, such as Sleep, Auto or Turbo. Circulators can also be pointed in a
direction: Fixed, Horizontal, Vertical, Both or Pan-tilt. Whatever extra
switches your model has, such as panel sounds, display or child lock, appear
too.

**Ceiling fans.** The light shows up as its own separate device with brightness
and warm/cool control, so it works with Room Lighting, groups, and anything that
adjusts your lights through the day. Reverse is one of the fan's modes rather
than a separate button.

**Humidifiers.** Humidity reading, target humidity, mist level, mode, and your
model's switches. Setting a humidity target does the right thing per mode, which
matters because these devices remember a separate target for Auto and for Sleep.

## Install

### With Hubitat Package Manager

Search for **Dreo Integration** and install it. HPM will ask which optional
drivers you want; pick the ones matching the devices you own. Updates then come
through HPM like anything else.

### By hand

You only need the drivers for the devices you actually own.

| If you have | Install from `drivers/` |
| --- | --- |
| Any fan, circulator or purifier | `dreo_fan.groovy` |
| A ceiling fan | `dreo_fan.groovy` **and** `dreo_fan_light.groovy` |
| A humidifier | `dreo_humidifier.groovy` |
| An air conditioner or heater | `dreo_basic_device.groovy` |

**Add the drivers before the app.** If you do it the other way round, adding
devices fails with a "driver not found" error.

1. **Drivers Code** > New Driver > paste each driver you need > Save.
2. **Apps Code** > New App > paste `apps/dreo_integration.groovy` > Save.
3. **Apps** > Add User App > **Dreo Integration**.

## Setup

1. Enter the same Dreo email and password you use in the Dreo mobile app.
2. Tap **Log in & find devices**. This only shows you what is on your account,
   it does not add anything yet.
3. Tick the devices you want under **Devices to control**, then tap **Done**.
   They are added when you save.
4. Choose how often Hubitat should check on them. Five minutes is the default.

Air conditioners and heaters are skipped unless you turn on **Add non-fan device
types using the basic driver** under Diagnostics.

## Upgrading from 0.1

Paste the new code over your existing entries in **Apps Code** and **Drivers
Code** rather than creating new ones. The names have not changed, and ending up
with two of each is confusing.

`Dreo Fan` replaces `Dreo Tower Fan` and can do everything the old driver could,
so your existing rules and dashboards keep working. Your fans stay on the old
driver until you move them across:

1. On the device page, change **Type** to `Dreo Fan`, then **Save Device**.
2. Go back into the Dreo app and tap **Done**, so the new driver picks up your
   device's details.

Nothing gets deleted. `Dreo Tower Fan` is no longer shipped.

## Things to know

- **Removing a device's tick mark deletes it**, along with any dashboard tiles
  or rules pointing at it. Only untick something you actually want gone.
- **Everything goes through Dreo's servers.** There is no local control, so if
  your internet is down, nothing works. That is a limitation of the devices, not
  of this integration.
- **Hubitat's Settings > Swap Device does not work on devices created by an
  app.** That applies to every app-based integration, not just this one. To
  repoint rules at a re-created device, use the device's "In Use By" list, or
  copy the rule and substitute the device. This is a different thing from
  changing a device's **Type**, which does work and is how you move between
  drivers.

## Not supported yet

- **Exact oscillation angles.** Turning oscillation on and off works, and so
  does choosing a direction, but setting the precise sweep limits does not.
- **Extra sensor readings.** Humidity on a humidifier works. Anything else your
  device measures is not shown as its own reading yet.
- **Colour-changing ambient lighting** on the models that have it.
- **Air conditioners, heaters and dehumidifiers** get on/off and mode only.

Most of these are possible, just not done. If one of them is the thing standing
between you and using this, say so in an issue, since knowing somebody actually
wants it makes a difference to what gets built next.

## Troubleshooting and reporting problems

Every device page has two buttons worth knowing about. **logCapabilities**
prints what the device told Hubitat it can do, and **logRawState** prints
everything it is currently reporting. Both go to the Logs page.

The app also has a **Diagnostics** section, which can:

- Save all of the above to a file, which you will find under Settings > File
  Manager as `dreo-dump.txt`
- Show you exactly which internal values changed when you press a button on the
  device itself, which is the quickest way to work out why something is not
  behaving

Serial numbers are always replaced before anything is written out, and there is
a tick box to replace device names too, which is worth using if yours describe
your home.

If you are reporting a problem, that file plus a note of what you did, what you
expected and what happened instead is the most useful thing you can post. Turn
on **Enable debug logging** first, then turn it back off afterwards, since it is
noisy.

## For developers: adding a device type

The app maps Dreo's `deviceType` to a driver via `DRIVER_FOR_TYPE` in
`dreo_integration.groovy`. Anything unmapped that still reports fan support
falls through to `Dreo Fan`, which is entirely config-driven. Climate devices
are excluded from that fallback by `advertisesFan()`, since an air conditioner
carries a fan config for its internal fan without being a fan.

To add a type properly, write a driver and add one entry, for example
`"hac": "Dreo Air Conditioner"`. The parent app forwards each device's fan,
light, select, sensor and humidifier config blocks along with `entitySupports`,
and the driver owns the per-type semantics.

Start by capturing the device's config with **Diagnostics > Capture device
list**, which describes everything the device can do. The upstream
[hass-dreoverse](https://github.com/dreo-team/hass-dreoverse) source is the
authoritative reference for field names and behaviour.

## Credits & license

Ported from the official Dreo Home Assistant integration
([dreo-team/hass-dreoverse](https://github.com/dreo-team/hass-dreoverse)) and its
`pydreo-cloud` transport library, both MIT-licensed. Upstream license reproduced
in [`LICENSE-dreoverse.txt`](LICENSE-dreoverse.txt).

Thanks also to [Jeff Steinbok](https://github.com/JeffSteinbok/hass-dreo), whose
earlier community integration the official one credits.
