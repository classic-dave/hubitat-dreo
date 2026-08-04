/**
 * Dreo Fan - Hubitat driver, child of the Dreo Integration app  (0.2.0)
 *
 * The default Dreo driver. Serves any device whose behaviour is fully described
 * by its own config payload rather than by hardcoded model knowledge:
 *
 *   fan              DR-HTF*            (tower fans)
 *   ceiling_fan      DR-HCF*            (ceiling fans; light handled by a child)
 *   circulation_fan  DR-HAF*, DR-HPF*   (air circulators)
 *   hap              DR-HAP*            (air purifiers)
 *   ...and any future type that advertises fan support in entitySupports
 *
 * Precise oscillation-angle limits are deliberately not implemented: every
 * angle directive takes a struct whose keys vary by geometry, and the four
 * geometries need read-merge-write handling that is not worth the complexity
 * yet. Oscillation on/off and direction are unaffected. Use sendDesired to set
 * angles by hand, e.g. {"hoscrange":{"L":30,"R":30}}.
 *
 * Supersedes Dreo Tower Fan: a strict superset of its attributes and commands,
 * so existing rules and dashboards keep working after switching Type. See the
 * oscillation and display notes below for the two compatibility shims.
 *
 * Everything model-specific (speed range, preset modes, oscillation options,
 * angle ranges, which toggles exist) is read from the device's config at
 * discovery. Nothing here is per-model, so a circulator geometry this driver has
 * never seen should still work.
 *
 * Ported from the official Dreo Home Assistant integration
 * (dreo-team/hass-dreoverse, MIT), specifically:
 *   fan.DreoCirculationFan       - power / speed / mode semantics
 *   select.DreoGenericModeSelect - config-driven enum selects (oscmode etc.)
 *   status_dependency            - conditional field availability
 * Independent Hubitat port; not affiliated with or endorsed by Dreo.
 *
 * NOT verified against hardware. Written from upstream source plus a real
 * /api/device/list payload. Report problems in the Hubitat community thread.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.2.0"

// Config key in toggle_entity_config -> semantic attribute here.
// LEGACY: Dreo Tower Fan exposed the led_switch display toggle as "adaptiveDisplay".
// When a device has no separate lightsensor_switch, this driver mirrors "display"
// onto "adaptiveDisplay" so rules written against the old driver keep working.
// NB: "light" and "displayAdaptive" are DIFFERENT toggles and can coexist on one
// device (an HAP002S reports led_switch "light" AND lightsensor_switch "Display
// Adaptive"), so they must not be collapsed into a single attribute.
@Field static final Map TOGGLE_ATTR_FOR_KEY = [
    "panelSound"     : "panelSound",       // mute_switch
    "childLock"      : "childLock",        // childlock_switch
    "light"          : "display",          // led_switch
    "displayAdaptive": "adaptiveDisplay",  // lightsensor_switch
    "ledDisplay"     : "display"           // led_switch, humidifier spelling
]

metadata {
    definition(name: "Dreo Fan", namespace: "community", author: "classic-dave") {
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

        attribute "oscillating", "string"              // on/off view, works on both mechanisms
        attribute "oscillationMode", "string"          // enum models only (oscmode)
        attribute "oscillationModeOptions", "string"   // JSON list, for dashboards/rules

        attribute "panelSound", "string"
        attribute "childLock", "string"
        attribute "display", "string"
        attribute "adaptiveDisplay", "string"

        attribute "selectStates", "string"   // JSON: every config-driven select and its value

        command "togglePower"
        command "setExactSpeed", [[name: "level*", type: "NUMBER", description: "Exact speed within the fan's range"]]
        command "speedUp"
        command "speedDown"
        command "setPresetMode", [[name: "mode*", type: "ENUM", constraints: modeConstraints()]]
        command "setOscillate", [[name: "oscillate*", type: "ENUM", constraints: ["on", "off"]]]
        command "toggleOscillate"
        command "setOscillationMode", [[name: "mode*", type: "ENUM", constraints: oscModeConstraints()]]
        command "setPanelSound", [[name: "panelSound*", type: "ENUM", constraints: ["on", "off"]]]
        command "setChildLock", [[name: "childLock*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDisplay", [[name: "display*", type: "ENUM", constraints: ["on", "off"]]]
        command "setAdaptiveDisplay", [[name: "adaptiveDisplay*", type: "ENUM", constraints: ["on", "off"]]]

        // Long tail: any select this device advertises with no dedicated command
        // above. Names come from the device's own config; see selectStates.
        command "setSelectOption", [[name: "select*", type: "STRING", description: "State field name, e.g. vfixed_angle_range"],
                                    [name: "option*", type: "STRING", description: "One of the options listed in selectStates"]]
        command "logCapabilities"
        command "logRawState"
        command "sendDesired", [[name: "json*", type: "STRING",
                                 description: "Raw payload for probing, e.g. {\"oscmode\":\"Horizontal\"}"]]
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def modeConstraints()    { return (state?.presetModes ?: ["Normal", "Natural", "Sleep", "Auto"]) }
def oscModeConstraints() { return (state?.oscOptions ?: ["Fixed", "Horizontal", "Vertical", "Both"]) }

// ---------- Lifecycle ----------

def installed() { sendEvent(name: "driverVersion", value: DRIVER_VERSION); runIn(2, "refresh") }

def updated() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (logEnable) runIn(1800, "logsOff")
    runIn(2, "refresh")
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

// Called by the parent at discovery to hand down this device's own config.
def configureMeta(Map m) {
    state.deviceType  = m.deviceType
    state.model       = m.model
    state.speedMin    = m.speedMin ?: 1
    state.speedMax    = m.speedMax
    state.presetModes = m.presetModes ?: []
    state.fanConfigRaw = m.fanConfig
    state.sensorConfigRaw = m.sensorConfig

    // Boolean oscillate (tower fans) unless the config advertises an oscmode
    // enum, which applySelectConfig clears this for.
    state.oscBoolField = (m.oscKey == "oscillate") ? "oscillate" : null

    applyToggleConfig(m.toggles)
    applySelectConfig(m.selectConfig)
    manageLightChild(m)

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (m.deviceType) sendEvent(name: "deviceType", value: m.deviceType)
    if (m.model)      sendEvent(name: "model", value: m.model)
    sendEvent(name: "supportedFanSpeeds",
              value: JsonOutput.toJson(["low", "medium-low", "medium", "medium-high", "high", "on", "off", "auto"]))

    log.info "Dreo ${m.model ?: m.deviceType}: speed ${state.speedMin}-${state.speedMax}, " +
             "modes ${state.presetModes}, selects ${(state.selects ?: [:]).keySet()}, " +
             "numbers ${(state.numbers ?: [:]).keySet()}, toggles ${state.toggleMap}"
}

// toggle_entity_config -> wire field -> semantic attribute
private void applyToggleConfig(List toggles) {
    def map = [:]      // wire field -> attribute
    def labels = [:]   // attribute -> human label from the API
    (toggles ?: []).each { t ->
        def attr = TOGGLE_ATTR_FOR_KEY[t.key]
        if (attr && t.field) {
            map[t.field] = attr
            if (t.label) labels[attr] = t.label
        }
    }
    state.toggleMap    = map
    state.toggleLabels = labels
    // See LEGACY note above the toggle map.
    state.legacyDisplayAlias = (map.containsValue("display") && !map.containsValue("adaptiveDisplay"))
}

// select_entity_config -> config-driven enum selects. Shape per upstream
// select.DreoGenericModeSelect: each entry carries the state field it reflects,
// the directive to write, its options, and any availability dependencies.
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

    // oscmode is the common case and gets a first-class command + attribute.
    def osc = selects["oscmode"]
    state.oscOptions = osc?.options ?: []
    if (osc) {
        state.oscBoolField = null   // enum wins over the boolean field
        sendEvent(name: "oscillationModeOptions", value: JsonOutput.toJson(osc.options))
    }
}

// ---------- Light child (ceiling fans) ----------
//
// A ceiling fan is one Dreo device with two logical entities. Modelling the light
// as a component child rather than as extra commands here means Room Lighting,
// Groups, dimmer tiles, circadian apps and Google/Alexa all see a real light. It
// also keeps polling at one API call per fan, since only this device is a child
// of the app.

private String lightDni() { "${device.deviceNetworkId}-light" }

private void manageLightChild(Map m) {
    def lc = m.lightConfig
    def supports = (m.entitySupports ?: []).collect { it?.toString()?.toLowerCase() }
    def configured = (lc instanceof Map) && !lc.isEmpty()
    // Prefer the device's own entitySupports; fall back to config presence when
    // a model doesn't publish the list.
    def hasLight = configured && (supports.isEmpty() || supports.contains("light"))

    def child = getChildDevice(lightDni())
    if (hasLight && !child) {
        try {
            addChildDevice("community", "Dreo Fan Light", lightDni(),
                           [name: "${device.displayName} Light", isComponent: true])
            log.info "Created light child for ${device.displayName}"
        } catch (e) {
            log.error "Could not create the light child for ${device.displayName}: " +
                      "is the 'Dreo Fan Light' driver installed? ${e}"
        }
    } else if (!hasLight && child) {
        log.info "Removing light child for ${device.displayName}; it no longer reports a light"
        deleteChildDevice(lightDni())
    }

    state.hasLight = hasLight
    state.lightConfig = configured ? lc : null
}

private void updateLightChild(Map props) {
    def child = getChildDevice(lightDni())
    if (!child) return
    def evts = []
    if (props.light_switch != null) evts << [name: "switch", value: (props.light_switch as boolean) ? "on" : "off"]
    if (props.brightness != null)   evts << [name: "level", value: (props.brightness as Integer), unit: "%"]
    if (props.colortemp != null)    evts << [name: "colorTemperaturePercent", value: (props.colortemp as Integer)]
    if (evts) child.parse(evts)
}

private Integer clampFromConfig(String key, Integer value, Integer dLo, Integer dHi) {
    def r = state.lightConfig?.get(key)
    def lo = (r instanceof List && r.size() >= 2) ? (r[0] as Integer) : dLo
    def hi = (r instanceof List && r.size() >= 2) ? (r[1] as Integer) : dHi
    return Math.max(lo, Math.min(hi, value))
}

// Component callbacks. The child speaks percent in both directions; kelvin
// conversion lives in the child because the endpoints are its preferences.
def componentOn(cd)  { def r = sendControl([light_switch: true]);  if (r?.ok) cd.parse([[name: "switch", value: "on"]]) }
def componentOff(cd) { def r = sendControl([light_switch: false]); if (r?.ok) cd.parse([[name: "switch", value: "off"]]) }
def componentRefresh(cd) { refresh() }

def componentSetLevel(cd, level) {
    def pct = level as Integer
    if (pct <= 0) { componentOff(cd); return }   // Hubitat treats level 0 as off
    pct = clampFromConfig("brightness_percentage", pct, 1, 100)
    def r = sendControl([light_switch: true, brightness: pct])
    if (r?.ok) cd.parse([[name: "switch", value: "on"], [name: "level", value: pct, unit: "%"]])
}

def componentSetColorTempPercent(cd, percent) {
    def pct = clampFromConfig("color_temperature_range", percent as Integer, 1, 100)
    def r = sendControl([colortemp: pct])
    if (r?.ok) cd.parse([[name: "colorTemperaturePercent", value: pct]])
    else log.warn "Setting colour temperature to ${pct}% failed: status=${r?.status} data=${r?.data}. " +
                  "If this only fails while the light is off, please report it."
}

// ---------- Status dependencies (port of upstream status_dependency.py) ----------

// Fields can be conditionally unavailable. fixed_angle only applies when the
// device is on AND oscmode is Fixed, for instance. Returns true when no rules.
private boolean depsSatisfied(List deps, Map props) {
    if (!deps) return true
    Boolean combined = null
    deps.each { d ->
        def name = d?.directive_name?.toString()
        if (!name) return
        def cond    = (d.condition ?: "and").toString().toLowerCase()
        def allowed = (d.dependency_values instanceof List) ? d.dependency_values : []
        // "is_on" is synthetic upstream, backed by power_switch.
        def current = (name == "is_on") ? props?.power_switch : props?.get(name)
        def matched = (current != null) && allowed.any { it?.toString() == current?.toString() }
        if (combined == null)        combined = matched
        else if (cond == "or")       combined = (combined || matched)
        else                         combined = (combined && matched)
    }
    return (combined == null) ? true : combined
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

    if (state.hasLight) updateLightChild(props)

    // Oscillation. Tower fans report a boolean; circulators report an oscmode
    // enum. Publish "oscillating" for both so rules stay portable.
    if (props.containsKey("oscillate")) {
        state.oscBoolField = "oscillate"
        sendEvent(name: "oscillating", value: (props.oscillate as boolean) ? "on" : "off")
    } else if (props.oscmode != null) {
        sendEvent(name: "oscillating", value: (props.oscmode.toString() == "Fixed") ? "off" : "on")
    }

    // Config-driven selects
    def selectStates = [:]
    (state.selects ?: [:]).each { field, cfg ->
        def val = props[field]
        selectStates[field] = [value: val, available: depsSatisfied(cfg.deps, props), options: cfg.options]
        if (field == "oscmode" && val != null) sendEvent(name: "oscillationMode", value: val.toString())
    }
    sendEvent(name: "selectStates", value: JsonOutput.toJson(selectStates))

    // Toggles
    def present = []
    (state.toggleMap ?: [:]).each { field, attr ->
        if (props.containsKey(field)) {
            present << field
            def v = (props[field] as boolean) ? "on" : "off"
            sendEvent(name: attr, value: v)
            if (attr == "display" && state.legacyDisplayAlias) sendEvent(name: "adaptiveDisplay", value: v)
        }
    }
    state.toggleFields = present

    if (logEnable) log.debug "Dreo ${device.displayName} state: ${props}"
}

// ---------- Control ----------

private Map sendControl(Map desired) {
    def sn = getDataValue("deviceSn")
    if (!sn) { log.warn "Dreo child has no deviceSn. Re-run discovery in the Dreo Integration app"; return [ok: false] }
    def r = parent.deviceControl(sn, desired)
    if (r?.ok) runIn(3, "refresh")   // re-read; Auto mode and dependencies may override
    return r
}

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
    setExactSpeed(levelFromNamed(s))
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
    def r = sendControl([power_switch: true, speed: lvl])
    if (r?.ok) {
        sendEvent(name: "switch", value: "on")
        sendEvent(name: "speedLevel", value: lvl)
        sendEvent(name: "speed", value: namedFromLevel(lvl))
    }
}

def speedUp()   { setExactSpeed(((device.currentValue("speedLevel") ?: state.speedMin ?: 1) as Integer) + 1) }
def speedDown() { setExactSpeed(((device.currentValue("speedLevel") ?: state.speedMin ?: 1) as Integer) - 1) }

def setPresetMode(mode) {
    def valid = state.presetModes
    if (valid && !(mode in valid)) log.warn "Preset mode '${mode}' not in ${valid}; sending anyway"
    def r = sendControl([mode: mode])
    if (r?.ok) sendEvent(name: "mode", value: mode)
}

// Boolean oscillation. On tower fans this writes the oscillate field directly.
// On enum models it maps onto oscmode so the same rule works on both: on ->
// Horizontal (or the first non-Fixed option), off -> Fixed.
def setOscillate(value) {
    def wantOn = (value?.toString() == "on")

    if (state.oscBoolField) {
        def r = sendControl([(state.oscBoolField): wantOn])
        if (r?.ok) sendEvent(name: "oscillating", value: wantOn ? "on" : "off")
        return
    }

    def osc = (state.selects ?: [:])["oscmode"]
    if (!osc) { log.warn "This device reports no oscillation control."; return }
    def opts = osc.options ?: []
    def target = wantOn ? (opts.contains("Horizontal") ? "Horizontal" : opts.find { it != "Fixed" })
                        : (opts.contains("Fixed") ? "Fixed" : null)
    if (!target) {
        log.warn "Cannot map setOscillate(${value}) onto ${opts}. Use setOscillationMode instead."
        return
    }
    log.info "setOscillate(${value}) maps to oscmode '${target}' on this model"
    setSelectOption("oscmode", target)
}

def toggleOscillate() { setOscillate(device.currentValue("oscillating") == "on" ? "off" : "on") }

def setOscillationMode(mode) { setSelectOption("oscmode", mode?.toString()) }

// Generic config-driven select. Upstream powers the device on when setting one,
// so a select from an off state doesn't silently no-op.
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
    if (!depsSatisfied(cfg.deps, state.raw ?: [:])) {
        log.warn "'${selectName}' is not currently settable; its config requires ${cfg.deps}. Sending anyway."
    }
    def desired = [(cfg.directive): option]
    if (device.currentValue("switch") != "on") desired["power_switch"] = true
    def r = sendControl(desired)
    if (r?.ok && selectName == "oscmode") sendEvent(name: "oscillationMode", value: option)
}

// ---------- Toggles ----------

private String fieldForAttr(String attr) {
    def map = state.toggleMap ?: [:]
    def candidates = map.findAll { field, a -> a == attr }.keySet() as List
    def present = state.toggleFields ?: []
    return candidates.find { it in present } ?: candidates.find { true }
}

private setToggleField(String attr, String value) {
    if (!state.toggleFields) refresh()
    def f = fieldForAttr(attr)
    if (!f) { log.warn "No '${attr}' toggle on this device (map: ${state.toggleMap}, seen: ${state.toggleFields})."; return }
    def r = sendControl([(f): (value == "on")])
    if (r?.ok) {
        sendEvent(name: attr, value: value)
        log.info "Set ${attr} (${f}) = ${value}"
    } else {
        log.warn "Set ${attr} (${f}) = ${value} failed: status=${r?.status} data=${r?.data}"
    }
}

def setPanelSound(value)      { setToggleField("panelSound", value) }
def setChildLock(value)       { setToggleField("childLock", value) }
def setDisplay(value)         { setToggleField("display", value) }
def setAdaptiveDisplay(value) {
    // Legacy alias: on a device with only a led_switch display toggle, the old
    // Tower Fan driver called it adaptiveDisplay. Route there rather than failing.
    if (state.legacyDisplayAlias) { setToggleField("display", value); return }
    setToggleField("adaptiveDisplay", value)
}

// ---------- Diagnostics (available on every device) ----------

// Probe any field without shipping a new driver. Dreo's cloud validates
// server-side and rejects payloads it doesn't accept.
def sendDesired(String json) {
    if (!json?.trim()) { log.warn "sendDesired: nothing to send"; return }
    def desired
    try {
        desired = new JsonSlurper().parseText(json)
    } catch (e) {
        log.error "sendDesired: could not parse JSON: ${e.message}. Expected something like {\"speed\":3}"
        return
    }
    if (!(desired instanceof Map)) { log.error "sendDesired: payload must be a JSON object, e.g. {\"speed\":3}"; return }
    def r = sendControl(desired)
    if (r?.ok) log.info "sendDesired ${JsonOutput.toJson(desired)} accepted"
    else       log.warn "sendDesired ${JsonOutput.toJson(desired)} rejected: status=${r?.status} data=${r?.data}"
}

// Last known property map, pretty-printed for pasting into a bug report.
def logRawState() {
    if (!state.raw) { log.info "No state captured yet. Tap Refresh first."; return }
    log.info "Dreo ${device.displayName} raw state:\n" + JsonOutput.prettyPrint(JsonOutput.toJson(state.raw))
}

// Print everything this device told us it can do, for bug reports.
def logCapabilities() {
    log.info "Dreo ${device.displayName} capabilities:\n" + JsonOutput.prettyPrint(JsonOutput.toJson([
        deviceType: state.deviceType, model: state.model,
        speedRange: [state.speedMin, state.speedMax], presetModes: state.presetModes,
        selects: state.selects,
        toggles: state.toggleMap, toggleLabels: state.toggleLabels,
        // Angles and sensors are deliberately not driven by this driver. Both
        // are reported raw here so they can be inspected for future work; every
        // value is also in logRawState, and the full config is in the app's
        // device-list capture.
        angleConfigRaw : [fixed_angle: state.fanConfigRaw?.fixed_angle,
                          oscrange   : state.fanConfigRaw?.oscrange],
        sensorConfigRaw: state.sensorConfigRaw
    ]))
}

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
