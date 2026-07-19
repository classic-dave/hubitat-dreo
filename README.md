# Dreo Fan integration for Hubitat

Cloud control of Dreo smart fans on Hubitat, using Dreo's OpenAPI. A parent app
handles the account, discovery, and polling; a child driver exposes each fan.

> Unofficial and community-maintained. Not affiliated with or endorsed by Dreo.
> Ported from the official Dreo Home Assistant integration
> ([dreo-team/hass-dreoverse](https://github.com/dreo-team/hass-dreoverse), MIT).

## Supported devices

`DR-HTF` tower fans (the `S`-suffix models with cloud/API support), e.g. Pilot
Max S (DR-HTF004S), Nomad One S (DR-HTF007S). Speed range is read from the API
per model (1-4 up to 1-12), so any DR-HTF tower fan should work. Other Dreo
device types (circulators, ceiling fans, AC, humidifiers) are **not** supported
yet but the app is structured to add them — see [Adding device types](#adding-device-types).

Exposed: on/off, named + exact speed, oscillation, preset mode (Sleep/Auto/
Natural/Normal), panel sound, and the model's display toggle.

## Install

Order matters — **add the driver first, then the app**, or child-device creation
fails with a "driver not found" error.

1. **Drivers Code** → New Driver → paste `Dreo_Tower_Fan.groovy` → Save.
2. **Apps Code** → New App → paste `Dreo_Integration.groovy` → Save.
3. **Apps** → Add User App → **Dreo Integration**.

## Setup

1. Enter your Dreo account email and password (the same ones you use in the Dreo
   mobile app).
2. Tap **Log in & find devices**. This only *lists* your fans — it doesn't add
   them yet.
3. Select the fans you want under **Devices to control**, then tap **Done**. The
   child devices are created (and removed if you deselect) when you save.
4. Set a polling interval (default 5 min) for state refresh.

## Caveats

- **Deselecting a fan deletes its child device**, along with any dashboard tiles
  and rule references pointing at it. Only deselect if you mean to remove it.
- **Child devices can't be used with Settings → Swap Device.** This is a Hubitat
  restriction on all app-based integrations, not specific to this one. If you
  need to repoint rules to a re-created device, do it manually (use the device's
  "In Use By" list) or clone the rule with device substitution.
- Cloud-only; there's no local control. Requires internet and the Dreo cloud.

## Adding device types

The app maps the API's `deviceType` to a child driver via `DRIVER_FOR_TYPE` in
`Dreo_Integration.groovy`. To add a type, write a driver for it and add one
entry (e.g. `"hac": "Dreo Air Conditioner"`). The parent forwards each device's
speed range, preset modes, and `toggle_entity_config` to the driver, which owns
the per-type semantics.

## Credits & license

Ported from the official Dreo Home Assistant integration
([dreo-team/hass-dreoverse](https://github.com/dreo-team/hass-dreoverse)) and its
`pydreo-cloud` transport library, both MIT-licensed. Upstream license reproduced
in [`LICENSE-dreoverse.txt`](LICENSE-dreoverse.txt).

Thanks also to [Jeff Steinbok](https://github.com/JeffSteinbok/hass-dreo), whose
earlier community integration the official one credits.
