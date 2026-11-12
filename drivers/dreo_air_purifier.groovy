/**
 * Dreo Air Purifier - Hubitat driver, child of the Dreo Integration app  (0.4.0)
 *
 * Split out of Dreo Fan, which a purifier was using until now. A purifier has
 * no oscillation, no light and no adaptive display, so those commands and
 * attributes are gone. Speed, power, preset modes and the config-driven
 * toggles work the same way they do on a fan.
 *
 * Two things here are untested against real hardware, because the author owns
 * no purifier:
 *
 *  - Filter life is built from whatever sensor_entity_config holds. The shape
 *    is taken from upstream sensor.py: one entry per sensor, each carrying
 *    attr_name, directive_name, state_attr_name and a unit. If the config is
 *    missing or shaped differently, this logs it and carries on rather than
 *    failing. logCapabilities prints the raw config for a bug report.
 *  - setExactSpeed switches to the manual preset first, which is what
 *    DreoHapFan.async_execute_hap_command does upstream. Without it a speed
 *    change on a purifier in Auto is accepted and ignored. The manual mode is
 *    found by name in this device's own preset list, never guessed.
 *
 * If this driver misbehaves, change the device's Type back to "Dreo Fan" and
 * re-save. Nothing here deletes or recreates the device.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.4.0"

// Wire field -> semantic attribute, same mapping Dreo Fan uses, minus the
// ones a purifier does not have.
@Field static final Map TOGGLE_ATTR_FOR_KEY = [
    "muteon"   : "panelSound",
    "mute"     : "panelSound",
    "childlock": "childLock",
    "lock"     : "childLock",
    "light"    : "display",
    "ledDisplay": "display"
]

metadata {
    definition(name: "Dreo Air Purifier", namespace: "community", author: "classic-dave") {
        capability "Actuator"
        capability "Switch"
        capability "FanControl"
        capability "Refresh"

        attribute "driverVersion", "string"
        attribute "deviceType", "string"
        attribute "model", "string"
        attribute "connectionStatus", "string"
        attribute "speedLevel", "number"
        attribute "mode", "string"

        attribute "panelSound", "string"
        attribute "childLock", "string"
        attribute "display", "string"

        attribute "filterLife", "number"      // percent, when the device reports one
        attribute "sensorStates", "string"    // JSON: every sensor this device reports
        attribute "selectStates", "string"    // JSON: every config-driven select and its value

        command "togglePower"
        command "setExactSpeed", [[name: "level*", type: "NUMBER", description: "Exact speed within the purifier's range"]]
        command "speedUp"
        command "speedDown"
        command "setPresetMode", [[name: "mode*", type: "STRING", description: "A preset mode this purifier has. Listed under Preset Modes in State Variables"]]
        command "cyclePresetMode"
        command "setPanelSound", [[name: "panelSound*", type: "ENUM", constraints: ["on", "off"]]]
        command "setChildLock", [[name: "childLock*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDisplay", [[name: "display*", type: "ENUM", constraints: ["on", "off"]]]

        command "setSelectOption", [[name: "select*", type: "STRING", description: "State field name"],
                                    [name: "option*", type: "STRING", description: "One of the options listed in selectStates"]]
        command "logCapabilities"
        command "logRawState"
        command "sendDesired", [[name: "json*", type: "STRING",
                                 description: "Raw payload for probing, e.g. {\"mode\":\"Auto\"}"]]
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

// Commands that take a mode name take free text. A dropdown can't show a
// device's own list, because Hubitat builds a driver's command dropdowns once,
// with no device to read from. Matching ignores case and returns the device's
// own spelling.
private String matchOption(input, List valid) {
    def s = input?.toString()?.trim()
    if (!s) return null
    return valid?.find { it?.toString()?.equalsIgnoreCase(s) }?.toString()
}

// The entry after current in list, wrapping at the end. Starts at the first
// entry when current isn't in the list.
private String nextOption(current, List list) {
    if (!list) return null
    def i = list.findIndexOf { it?.toString()?.equalsIgnoreCase(current?.toString() ?: "") }
    return list[(i + 1) % list.size()]?.toString()
}

// ---------- Lifecycle ----------

def installed() { sendEvent(name: "driverVersion", value: DRIVER_VERSION); runIn(2, "refresh") }

def updated() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (logEnable) runIn(1800, "logsOff")
    runIn(2, "refresh")
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

def configureMeta(Map m) {
    state.deviceType  = m.deviceType
    state.model       = m.model
    state.speedMin    = m.speedMin ?: 1
    state.speedMax    = m.speedMax
    state.presetModes = m.presetModes ?: []
    state.fanConfigRaw = m.fanConfig
    state.sensorConfigRaw = m.sensorConfig

    applyToggleConfig(m.toggles)
    applySelectConfig(m.selectConfig)
    applySensorConfig(m.sensorConfig)

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (m.deviceType) sendEvent(name: "deviceType", value: m.deviceType)
    if (m.model)      sendEvent(name: "model", value: m.model)
    sendEvent(name: "supportedFanSpeeds",
              value: JsonOutput.toJson(["low", "medium-low", "medium", "medium-high", "high", "on", "off", "auto"]))

    log.info "Dreo purifier ${m.model ?: m.deviceType}: speed ${state.speedMin}-${state.speedMax}, " +
             "modes ${state.presetModes}, manual mode ${manualModeName() ?: 'not found'}, " +
             "sensors ${(state.sensors ?: [:]).keySet()}, selects ${(state.selects ?: [:]).keySet()}, " +
             "toggles ${state.toggleMap}"
}

private void applyToggleConfig(List toggles) {
    def map = [:]
    def labels = [:]
    (toggles ?: []).each { t ->
        def attr = TOGGLE_ATTR_FOR_KEY[t.key]
        if (attr && t.field) {
            map[t.field] = attr
            if (t.label) labels[attr] = t.label
        }
    }
    state.toggleMap    = map
    state.toggleLabels = labels
}

private void applySelectConfig(List selectConfig) {
    def selects = [:]
    (selectConfig ?: []).each { entry ->
        def sm = entry?.selector_mappings
        if (!(sm instanceof Map)) return
        def stateAttr = sm.state_attr_name?.toString()
        if (!stateAttr) return
        selects[stateAttr] = [
            directive: (sm.directive_name ?: stateAttr).toString(),
            label    : (sm.attr_name ?: stateAttr).toString(),
            options  : (sm.options instanceof List) ? sm.options.collect { it.toString() } : [],
            deps     : (sm.status_available_dependencies instanceof List) ? sm.status_available_dependencies : []
        ]
    }
    state.selects = selects
}

// sensor_entity_config, shape taken from upstream sensor.py: a map of sensor
// type to attr_name, directive_name, state_attr_name and unit. No DR-HAP
// payload has been seen, so anything unexpected is logged and skipped rather
// than treated as an error.
private void applySensorConfig(cfg) {
    def sensors = [:]
    if (cfg == null) {
        state.sensors = sensors
        return
    }
    if (!(cfg instanceof Map)) {
        log.warn "Dreo purifier ${device.displayName}: sensor_entity_config is a " +
                 "${typeName(cfg)}, not a map. Filter life is off. " +
                 "Please run logCapabilities and post the output."
        state.sensors = sensors
        return
    }
    cfg.each { key, entry ->
        if (!(entry instanceof Map)) {
            log.warn "Dreo purifier ${device.displayName}: sensor '${key}' is not a map; skipped."
            return
        }
        def field = (entry.state_attr_name ?: entry.directive_name ?: key)?.toString()
        if (!field) return
        sensors[field] = [label: (entry.attr_name ?: key).toString(),
                          unit : entry.unit?.toString(),
                          key  : key?.toString()]
    }
    state.sensors = sensors
    if (!sensors) {
        log.info "Dreo purifier ${device.displayName}: no sensors in sensor_entity_config, so no filter life."
    }
}

// Hubitat's sandbox rejects reflection on an object's class, so name the
// type with instanceof checks instead.
private String typeName(x) {
    if (x == null) return "null"
    if (x instanceof Map) return "Map"
    if (x instanceof List) return "List"
    if (x instanceof String) return "String"
    if (x instanceof Number) return "Number"
    if (x instanceof Boolean) return "Boolean"
    return "unrecognized type"
}

// The field a filter-life sensor reports under. Matched from this device's own
// sensor config rather than a guessed field name.
private String filterField() {
    return (state.sensors ?: [:]).keySet().find { it?.toString()?.toLowerCase()?.contains("filter") }
}

// The device's own name for its manual preset. Null when it has none.
private String manualModeName() {
    return (state.presetModes ?: []).find { it?.toString()?.toLowerCase() in ["manual", "normal manual"] }
}

// ---------- Refresh ----------

def refresh() { parent.deviceRefresh(device) }

def applyState(Map props) {
    if (props == null) return
    state.raw = props

    def poweredOn = (props.power_switch != null) ? (props.power_switch as boolean) : null
    if (poweredOn != null) sendEvent(name: "switch", value: poweredOn ? "on" : "off")

    if (props.speed != null) {
        def raw = props.speed as Integer
        sendEvent(name: "speedLevel", value: raw)
        sendEvent(name: "speed", value: (poweredOn == false) ? "off" : namedFromLevel(raw))
    }
    if (props.mode != null) sendEvent(name: "mode", value: props.mode.toString())
    sendEvent(name: "connectionStatus", value: (props.connected == false) ? "offline" : "connected")

    def sensorStates = [:]
    (state.sensors ?: [:]).each { field, cfg ->
        if (!props.containsKey(field)) return
        sensorStates[field] = [value: props[field], label: cfg.label, unit: cfg.unit]
    }
    if (sensorStates) sendEvent(name: "sensorStates", value: JsonOutput.toJson(sensorStates))
    def ff = filterField()
    if (ff && props[ff] != null) {
        try { sendEvent(name: "filterLife", value: (props[ff] as Integer), unit: "%") }
        catch (e) { log.warn "Dreo purifier: filter value '${props[ff]}' is not a number; left unset." }
    }

    def selectStates = [:]
    (state.selects ?: [:]).each { field, cfg ->
        selectStates[field] = [value: props[field], options: cfg.options]
    }
    sendEvent(name: "selectStates", value: JsonOutput.toJson(selectStates))

    def present = []
    (state.toggleMap ?: [:]).each { field, attr ->
        if (props.containsKey(field)) {
            present << field
            sendEvent(name: attr, value: (props[field] as boolean) ? "on" : "off")
        }
    }
    state.toggleFields = present

    if (logEnable) log.debug "Dreo purifier ${device.displayName} state: ${props}"
}

// ---------- Control ----------

private Map sendControl(Map desired) {
    def sn = getDataValue("deviceSn")
    if (!sn) { log.warn "Dreo child has no deviceSn. Re-run discovery in the Dreo Integration app"; return [ok: false] }
    def r = parent.deviceControl(sn, desired)
    if (r?.ok) runIn(3, "refresh")
    return r
}

private void logFailure(String what, Map r) {
    log.warn "${what} failed: status=${r?.status} data=${r?.data}"
}

def on()  { def r = sendControl([power_switch: true]);  if (r?.ok) sendEvent(name: "switch", value: "on")  else logFailure("on", r) }
def off() { def r = sendControl([power_switch: false]); if (r?.ok) { sendEvent(name: "switch", value: "off"); sendEvent(name: "speed", value: "off") } else logFailure("off", r) }
def togglePower() { (device.currentValue("switch") == "on") ? off() : on() }

def setSpeed(fanspeed) {
    def s = fanspeed?.toString()
    if (s == "off") { off(); return }
    if (s == "on")  { on();  return }
    if (s == "auto") {
        def auto = (state.presetModes ?: []).find { it?.toString()?.equalsIgnoreCase("auto") }
        if (auto) setPresetMode(auto) else on()
        return
    }
    setExactSpeed(levelFromNamed(s))
}

def cycleSpeed() {
    def order = ["low", "medium-low", "medium", "medium-high", "high"]
    def idx = order.indexOf(device.currentValue("speed"))
    setSpeed(order[(idx + 1) % order.size()])
}

// A purifier in Auto picks its own speed, so setting one without leaving Auto
// is accepted and ignored. Upstream switches to manual first; this does the
// same when the device has a manual preset.
def setExactSpeed(level) {
    def lvl = level as Integer
    def lo = (state.speedMin ?: 1) as Integer
    if (lvl < lo) lvl = lo
    if (state.speedMax) { def hi = state.speedMax as Integer; if (lvl > hi) lvl = hi }

    def desired = [power_switch: true, speed: lvl]
    def manual = manualModeName()
    def current = device.currentValue("mode")
    if (manual && current && !current.equalsIgnoreCase(manual)) {
        // Dreo's own integration sends lowercase here whatever the list says.
        desired["mode"] = "manual"
        log.info "Switching to ${manual} so the speed change takes effect (was ${current})"
    } else if (!manual) {
        log.warn "This purifier lists no manual preset (${state.presetModes}), so a speed change " +
                 "may be ignored while it picks its own speed. Please report it."
    }

    def r = sendControl(desired)
    if (r?.ok) {
        sendEvent(name: "switch", value: "on")
        sendEvent(name: "speedLevel", value: lvl)
        sendEvent(name: "speed", value: namedFromLevel(lvl))
        if (desired.mode) sendEvent(name: "mode", value: manual)
    } else {
        logFailure("setExactSpeed(${lvl})", r)
    }
}

def speedUp()   { setExactSpeed(((device.currentValue("speedLevel") ?: state.speedMin ?: 1) as Integer) + 1) }
def speedDown() { setExactSpeed(((device.currentValue("speedLevel") ?: state.speedMin ?: 1) as Integer) - 1) }

def setPresetMode(mode) {
    def valid = state.presetModes ?: []
    def target = mode?.toString()?.trim()
    if (!target) { log.warn "setPresetMode: no mode given. This purifier has: ${valid}"; return }
    if (valid) {
        def m = matchOption(target, valid)
        if (!m) { log.warn "'${target}' isn't a preset mode on this purifier, so nothing was sent. It has: ${valid}"; return }
        target = m
    }
    def r = sendControl([mode: target])
    if (r?.ok) sendEvent(name: "mode", value: target)
    else logFailure("setPresetMode(${target})", r)
}

// Steps to the next mode in this purifier's own list, in its own order.
def cyclePresetMode() {
    def valid = state.presetModes ?: []
    if (!valid) { log.warn "This purifier hasn't reported its preset modes. Tap Done in the Dreo Integration app, then try again."; return }
    setPresetMode(nextOption(device.currentValue("mode"), valid))
}

def setSelectOption(String selectName, String option) {
    def cfg = (state.selects ?: [:])[selectName]
    if (!cfg) {
        log.warn "No select '${selectName}' on this device. Available: ${(state.selects ?: [:]).keySet()}"
        return
    }
    if (cfg.options && !(option in cfg.options)) {
        log.warn "Option '${option}' not valid for ${selectName}. Valid: ${cfg.options}"
        return
    }
    def desired = [(cfg.directive): option]
    if (device.currentValue("switch") != "on") desired["power_switch"] = true
    def r = sendControl(desired)
    if (!r?.ok) logFailure("setSelectOption(${selectName}, ${option})", r)
}

// ---------- Toggles ----------

private setToggleField(String attr, String value) {
    if (!state.toggleFields) refresh()
    def map = state.toggleMap ?: [:]
    def candidates = map.findAll { field, a -> a == attr }.keySet() as List
    def present = state.toggleFields ?: []
    def f = candidates.find { it in present } ?: candidates.find { true }
    if (!f) { log.warn "No '${attr}' toggle on this device (map: ${map}, seen: ${present})."; return }
    def r = sendControl([(f): (value == "on")])
    if (r?.ok) {
        sendEvent(name: attr, value: value)
        log.info "Set ${attr} (${f}) = ${value}"
    } else {
        logFailure("Set ${attr} (${f}) = ${value}", r)
    }
}

def setPanelSound(value) { setToggleField("panelSound", value) }
def setChildLock(value)  { setToggleField("childLock", value) }
def setDisplay(value)    { setToggleField("display", value) }

// ---------- Diagnostics ----------

def sendDesired(String json) {
    if (!json?.trim()) { log.warn "sendDesired: nothing to send"; return }
    def desired
    try { desired = new JsonSlurper().parseText(json) }
    catch (e) { log.error "sendDesired: could not parse JSON: ${e.message}. Expected something like {\"mode\":\"Auto\"}"; return }
    if (!(desired instanceof Map)) { log.error "sendDesired: payload must be a JSON object, e.g. {\"mode\":\"Auto\"}"; return }
    def r = sendControl(desired)
    if (r?.ok) log.info "sendDesired ${JsonOutput.toJson(desired)} accepted"
    else       log.warn "sendDesired ${JsonOutput.toJson(desired)} rejected: status=${r?.status} data=${r?.data}"
}

def logRawState() {
    if (!state.raw) { log.info "No state captured yet. Tap Refresh first."; return }
    log.info "Dreo ${device.displayName} raw state: " + JsonOutput.toJson((state.raw))
}

def logCapabilities() {
    log.info "Dreo ${device.displayName} capabilities: " + JsonOutput.toJson(([
        deviceType: state.deviceType, model: state.model,
        speedRange: [state.speedMin, state.speedMax], presetModes: state.presetModes,
        manualMode: manualModeName(),
        selects: state.selects,
        toggles: state.toggleMap, toggleLabels: state.toggleLabels,
        sensors: state.sensors, filterField: filterField(),
        sensorConfigRaw: state.sensorConfigRaw
    ]))
}

// ---------- Speed mapping ----------

def namedFromLevel(level) {
    if (level == null) return "off"
    def lvl = level as Integer
    def lo = (state.speedMin ?: 1) as Integer
    def hi = (state.speedMax ?: 4) as Integer
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
    def hi = (state.speedMax ?: 4) as Integer
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
