/**
 * Dreo Tower Fan - Hubitat driver, child of the Dreo Integration app  (v0.1)
 *
 * Fan semantics only: capabilities, commands, named<->exact speed mapping, and
 * live-state mapping. All HTTP goes through the parent (deviceControl /
 * deviceRefresh). Covers DR-HTF tower fans reporting deviceType "fan".
 * Install this driver before adding the Dreo Integration app.
 *
 * Credit: ported from the official Dreo Home Assistant integration
 * (dreo-team/hass-dreoverse, MIT) and its pydreo-cloud transport library.
 * https://github.com/dreo-team/hass-dreoverse
 * Independent Hubitat port; not affiliated with or endorsed by Dreo.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

// Fallback wire-field -> attribute map for a fan whose discovery omitted
// toggle_entity_config; normally the map is built per-model at configureMeta.
@Field static final Map DEFAULT_TOGGLE_MAP =
    [mute_switch: "panelSound", lightsensor_switch: "adaptiveDisplay", led_switch: "adaptiveDisplay"]

metadata {
    definition(name: "Dreo Tower Fan", namespace: "community", author: "classic-dave") {
        capability "Switch"
        capability "FanControl"
        capability "Refresh"

        attribute "speedLevel", "number"
        attribute "oscillating", "string"
        attribute "mode", "string"
        attribute "connectionStatus", "string"
        attribute "panelSound", "string"
        attribute "adaptiveDisplay", "string"   // display toggle; wire field + label vary by model (see state.toggleLabels)

        command "togglePower"
        command "setExactSpeed", [[name: "level*", type: "NUMBER", description: "Exact speed within the fan's range"]]
        command "speedUp"
        command "speedDown"
        command "toggleOscillate"
        command "setOscillate", [[name: "oscillate*", type: "ENUM", constraints: ["on", "off"]]]
        command "setPanelSound", [[name: "panelSound*", type: "ENUM", constraints: ["on", "off"]]]
        command "setAdaptiveDisplay", [[name: "adaptiveDisplay*", type: "ENUM", constraints: ["on", "off"]]]
        command "setPresetMode", [[name: "mode*", type: "ENUM", constraints: modeConstraints()]]
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def modeConstraints() { return (state?.presetModes ?: ["Sleep", "Auto", "Natural", "Normal"]) }

// ---------- Lifecycle ----------

def installed() { runIn(2, "refresh") }
def updated()   { if (logEnable) runIn(1800, "logsOff"); runIn(2, "refresh") }
def logsOff()   { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

// Called by the parent at discovery to hand down per-fan metadata.
def configureMeta(Map m) {
    state.speedMin    = m.speedMin ?: 1
    state.speedMax    = m.speedMax
    state.oscKey      = m.oscKey ?: "oscillate"
    state.presetModes = m.presetModes ?: ["Sleep", "Auto", "Natural", "Normal"]
    applyToggleConfig(m.toggles)
    sendEvent(name: "supportedFanSpeeds",
              value: JsonOutput.toJson(["low", "medium-low", "medium", "medium-high", "high", "on", "off", "auto"]))
    if (logEnable) log.debug "Meta set: speed ${state.speedMin}-${state.speedMax}, osc '${state.oscKey}', modes ${state.presetModes}, toggles ${state.toggleMap}"
}

// Resolve the parent's toggle_entity_config into a wire-field -> attribute map
// (state.toggleMap) plus human labels (state.toggleLabels). The config-key ->
// attribute grouping is the only fan-specific knowledge kept here.
private void applyToggleConfig(List toggles) {
    def map = [:]      // wire field -> semantic attribute
    def labels = [:]   // semantic attribute -> human label
    (toggles ?: []).each { t ->
        def attr = toggleAttrForKey(t.key)
        if (attr && t.field) {
            map[t.field] = attr
            if (t.label) labels[attr] = t.label
        }
    }
    state.toggleMap    = map ?: DEFAULT_TOGGLE_MAP
    state.toggleLabels = labels
}

private String toggleAttrForKey(String key) {
    switch (key) {
        case "panelSound":      return "panelSound"       // mute_switch
        case "light":                                     // led_switch ("Display Auto off")
        case "displayAdaptive": return "adaptiveDisplay"  // lightsensor_switch ("Display Adaptive")
        default:                return null               // unknown toggle: not surfaced
    }
}

// ---------- Refresh (parent fetches, this maps the result) ----------

def refresh() { parent.deviceRefresh(device) }

// Called by the parent with the fan's raw live-state property map.
def applyState(Map props) {
    if (props == null) return
    def key = state.oscKey ?: "oscillate"
    def poweredOn = (props.power_switch != null) ? (props.power_switch as boolean) : null
    if (poweredOn != null) sendEvent(name: "switch", value: poweredOn ? "on" : "off")
    if (props.speed != null) {
        def raw = props.speed as Integer
        sendEvent(name: "speedLevel", value: raw)
        sendEvent(name: "speed", value: (poweredOn == false) ? "off" : namedFromLevel(raw))
    }
    if (props[key] != null)      sendEvent(name: "oscillating", value: props[key] ? "on" : "off")
    if (props.mode != null)      sendEvent(name: "mode", value: props.mode)
    sendEvent(name: "connectionStatus", value: (props.connected == false) ? "offline" : "connected")

    // Map each reported toggle field to its semantic attribute using the
    // per-model map resolved from toggle_entity_config; record fields seen.
    def map = state.toggleMap ?: DEFAULT_TOGGLE_MAP
    def present = []
    map.each { field, attr ->
        if (props.containsKey(field)) {
            present << field
            sendEvent(name: attr, value: (props[field] as boolean) ? "on" : "off")
        }
    }
    state.toggleFields = present
}

// ---------- Control (delegates send to parent) ----------

private Map sendControl(Map desired) {
    def sn = getDataValue("deviceSn")
    if (!sn) { log.warn "Dreo child has no deviceSn — re-run discovery in the Dreo Integration app"; return [ok: false] }
    def r = parent.deviceControl(sn, desired)
    if (r?.ok) runIn(3, "refresh")   // re-read real state; Auto mode may override speed
    return r
}

// ---------- Commands ----------

def on()  { def r = sendControl([power_switch: true]);  if (r?.ok) sendEvent(name: "switch", value: "on") }
def off() { def r = sendControl([power_switch: false]); if (r?.ok) { sendEvent(name: "switch", value: "off"); sendEvent(name: "speed", value: "off") } }
def togglePower() { (device.currentValue("switch") == "on") ? off() : on() }

def setSpeed(fanspeed) {
    def s = fanspeed?.toString()
    if (s == "off") { off(); return }
    if (s == "on")  { on();  return }
    if (s == "auto") {
        if ("Auto" in (state.presetModes ?: [])) setPresetMode("Auto") else on()
        return
    }
    def lvl = levelFromNamed(s) as Integer
    def r = sendControl([power_switch: true, speed: lvl])
    if (r?.ok) {
        sendEvent(name: "switch", value: "on")
        sendEvent(name: "speedLevel", value: lvl)
        sendEvent(name: "speed", value: s)
    }
}

def cycleSpeed() {
    def order = ["low", "medium-low", "medium", "medium-high", "high"]
    def idx = order.indexOf(device.currentValue("speed"))
    setSpeed(order[(idx + 1) % order.size()])
}

def setExactSpeed(level) {
    def lvl = level as Integer
    def lo = (state.speedMin ?: 1) as Integer
    if (lvl < lo) lvl = lo
    if (state.speedMax) { def hi = state.speedMax as Integer; if (lvl > hi) lvl = hi }
    def r = sendControl([speed: lvl])
    if (r?.ok) {
        sendEvent(name: "speedLevel", value: lvl)
        sendEvent(name: "speed", value: namedFromLevel(lvl))
    }
}

def speedUp()   { setExactSpeed(((device.currentValue("speedLevel") ?: state.speedMin ?: 1) as Integer) + 1) }
def speedDown() { setExactSpeed(((device.currentValue("speedLevel") ?: state.speedMin ?: 1) as Integer) - 1) }

def setOscillate(value) {
    def onoff = (value?.toString()?.toLowerCase() in ["on", "true", "1"])
    def key = state.oscKey ?: "oscillate"
    def r = sendControl([(key): onoff])
    if (r?.ok) sendEvent(name: "oscillating", value: onoff ? "on" : "off")
}

def toggleOscillate() { setOscillate(device.currentValue("oscillating") == "on" ? "off" : "on") }

def setPresetMode(mode) {
    def valid = state.presetModes
    if (valid && !(mode in valid)) log.warn "Preset mode '${mode}' not in ${valid}; sending anyway"
    def r = sendControl([mode: mode])
    if (r?.ok) sendEvent(name: "mode", value: mode)
}

// ---------- Auxiliary toggles (resolved from the per-model toggle map) ----------

// Wire field for a semantic attribute, preferring one this fan has reported.
private String fieldForAttr(String attr) {
    def map = state.toggleMap ?: DEFAULT_TOGGLE_MAP
    def candidates = map.findAll { field, a -> a == attr }.keySet() as List
    def present = state.toggleFields ?: []
    return candidates.find { it in present } ?: candidates.find { true }
}

private setToggleField(String attr, String value) {
    if (!state.toggleFields) refresh()
    def f = fieldForAttr(attr)
    if (!f) {
        log.warn "No '${attr}' toggle on this fan (map: ${state.toggleMap}, seen: ${state.toggleFields})."
        return
    }
    def r = sendControl([(f): (value == "on")])
    if (r?.ok) {
        sendEvent(name: attr, value: value)
        log.info "Set ${attr} (${f}) = ${value}"
    } else {
        log.warn "Set ${attr} (${f}) = ${value} failed: status=${r?.status} data=${r?.data}"
    }
}

def setPanelSound(value)      { setToggleField("panelSound", value) }
def setAdaptiveDisplay(value) { setToggleField("adaptiveDisplay", value) }

// ---------- Speed mapping (named FanControl enum <-> exact 1..N) ----------

def namedFromLevel(level) {
    if (level == null) return "off"
    def lvl = level as Integer
    def lo = (state.speedMin ?: 1) as Integer
    def hi = (state.speedMax ?: 12) as Integer
    if (hi <= lo) return "high"
    def pct = (double)(lvl - lo) / (double)(hi - lo)
    if (pct < 0.20) return "low"
    if (pct < 0.40) return "medium-low"
    if (pct < 0.60) return "medium"
    if (pct < 0.80) return "medium-high"
    return "high"
}

def levelFromNamed(named) {
    def lo = (state.speedMin ?: 1) as Integer
    def hi = (state.speedMax ?: 12) as Integer
    def span = (hi - lo)
    switch (named) {
        case "low":         return lo
        case "medium-low":  return lo + (Math.round(span * 0.25) as Integer)
        case "medium":      return lo + (Math.round(span * 0.50) as Integer)
        case "medium-high": return lo + (Math.round(span * 0.75) as Integer)
        case "high":        return hi
        default:            return lo
    }
}
