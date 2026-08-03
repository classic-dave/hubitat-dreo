/**
 * Dreo Fan Light - Hubitat component driver, child of Dreo Fan  (v0.2.0)
 *
 * The light on a Dreo ceiling fan. Created automatically by Dreo Fan when the
 * device's config advertises a light_entity_config, so it is never installed by
 * hand and never appears for a fan that has no light.
 *
 * Exists as a separate device rather than as extra commands on the fan so that
 * Room Lighting, Groups, dashboard dimmer tiles, circadian apps and Google/Alexa
 * all see a real dimmable colour-temperature light.
 *
 * COLOUR TEMPERATURE, PLEASE READ
 * The Dreo API has no concept of kelvin. It exposes colortemp as a 1-100 percent
 * value, and color_temperature_range in the device config is also a percent
 * range. Hubitat's ColorTemperature capability requires kelvin, so the endpoints
 * below have to come from somewhere. 2700K/6500K are what the official Home
 * Assistant integration hardcodes (light.DreoRegularLight), and nothing in the
 * API confirms them, nor which end of the percent scale is warm. Both are
 * preferences. If your light at 1% looks cool rather than warm, tick "Invert".
 *
 * Ported from dreo-team/hass-dreoverse (MIT).
 * Independent Hubitat port; not affiliated with or endorsed by Dreo.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.2.0"

metadata {
    definition(name: "Dreo Fan Light", namespace: "community", author: "classic-dave") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorTemperature"
        capability "Refresh"

        attribute "driverVersion", "string"
        attribute "colorTemperaturePercent", "number"   // the raw wire value
    }
    preferences {
        input name: "ctWarm", type: "number", title: "Kelvin at the warm end of the range",
              description: "Unverified default from the Home Assistant integration.", defaultValue: 2700
        input name: "ctCool", type: "number", title: "Kelvin at the cool end of the range",
              description: "Unverified default from the Home Assistant integration.", defaultValue: 6500
        input name: "ctInvert", type: "bool", title: "Invert: treat 1% as the cool end",
              description: "Tick if low percentages look cool rather than warm on your fan.", defaultValue: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() { sendEvent(name: "driverVersion", value: DRIVER_VERSION) }

def updated() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (logEnable) runIn(1800, "logsOff")
    // Endpoints may have changed; re-derive kelvin from the last known percent.
    if (state.lastPercent != null) publishColorTemp(state.lastPercent as Integer)
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ---------- Parent -> child ----------
// The parent speaks percent for colour temperature; conversion to kelvin happens
// here, because the endpoints are this device's preferences.
def parse(List description) {
    description.each { evt ->
        if (evt?.name == "colorTemperaturePercent") {
            publishColorTemp(evt.value as Integer)
        } else if (evt?.name) {
            sendEvent(evt)
        }
    }
}

private void publishColorTemp(Integer percent) {
    state.lastPercent = percent
    sendEvent(name: "colorTemperaturePercent", value: percent)
    def k = kelvinFromPercent(percent)
    sendEvent(name: "colorTemperature", value: k, unit: "K")
    sendEvent(name: "colorName", value: colorNameFor(k))
    if (logEnable) log.debug "colortemp ${percent}% -> ${k}K"
}

private Integer warmK() { (settings.ctWarm ?: 2700) as Integer }
private Integer coolK() { (settings.ctCool ?: 6500) as Integer }

private Integer kelvinFromPercent(Integer pct) {
    def p = Math.max(0, Math.min(100, pct ?: 0))
    if (settings.ctInvert) p = 100 - p
    return (warmK() + ((coolK() - warmK()) * (p / 100.0))) as Integer
}

private Integer percentFromKelvin(Integer kelvin) {
    def lo = warmK()
    def hi = coolK()
    if (hi == lo) return 50
    def k = Math.max(lo, Math.min(hi, kelvin ?: lo))
    def p = (((k - lo) / (double)(hi - lo)) * 100.0) as Integer
    if (settings.ctInvert) p = 100 - p
    return Math.max(0, Math.min(100, p))
}

private String colorNameFor(Integer k) {
    if (k < 2900) return "Warm White"
    if (k < 3500) return "Soft White"
    if (k < 4500) return "Neutral White"
    if (k < 5500) return "Cool White"
    return "Daylight"
}

// ---------- Child -> parent ----------

def on()      { parent?.componentOn(device) }
def off()     { parent?.componentOff(device) }
def refresh() { parent?.componentRefresh(device) }

def setLevel(level, ramp = null) {
    if (ramp != null && logEnable) log.debug "Transition time is not supported by the Dreo API; ignoring"
    parent?.componentSetLevel(device, level as Integer)
}

// Deliberately does NOT power the light on. Circadian apps adjust colour
// temperature on lights that are off, and turning them on would be a surprise.
def setColorTemperature(colortemperature, level = null, transitionTime = null) {
    if (level != null) setLevel(level)
    parent?.componentSetColorTempPercent(device, percentFromKelvin(colortemperature as Integer))
}
