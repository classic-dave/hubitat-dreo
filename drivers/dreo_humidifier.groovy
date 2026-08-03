/**
 * Dreo Humidifier - Hubitat driver, child of the Dreo Integration app  (v0.2.0)
 *
 * For Dreo humidifiers (deviceType "humidifier", DR-HHM*). Like the Dreo Fan
 * driver, everything model-specific is read from the device's own config: mode
 * list, humidity range, fog level range, calibration range, and which toggles
 * exist.
 *
 * THE DIRECTIVE GRAPH
 * The wire field that holds the humidity setpoint changes with the mode. The
 * device publishes this itself in humidity_mode_config.directive_graph:
 *
 *   Auto   -> writes rh_auto,   fog_level out of service
 *   Sleep  -> writes rh_sleep,  fog_level out of service
 *   Manual -> writes fog_level, rh_auto and rh_sleep out of service
 *
 * Every state payload contains rh_auto AND rh_sleep at once, so reading the
 * setpoint requires consulting the graph too; you cannot just take whichever
 * you find. The official Home Assistant integration applies the graph when
 * writing but not when reading (coordinator.process_humidifier_data assigns
 * rh_auto then unconditionally overwrites it with rh_sleep), so in Auto mode it
 * reports the Sleep setpoint. This driver applies the graph in both directions.
 *
 * Ported from dreo-team/hass-dreoverse (MIT).
 * Independent Hubitat port; not affiliated with or endorsed by Dreo.
 *
 * NOT verified against hardware. Written from upstream source plus a real
 * /api/device/list payload for a DR-HHM014S.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.2.0"

@Field static final Map TOGGLE_ATTR_FOR_KEY = [
    "panelSound"     : "panelSound",       // mute_switch
    "ledDisplay"     : "display",          // led_switch
    "light"          : "display",
    "dry"            : "dryMode",          // dry_switch
    "childLock"      : "childLock",
    "displayAdaptive": "adaptiveDisplay"
]

metadata {
    definition(name: "Dreo Humidifier", namespace: "community", author: "classic-dave") {
        capability "Actuator"
        capability "Switch"
        capability "RelativeHumidityMeasurement"
        capability "Refresh"

        attribute "driverVersion", "string"
        attribute "model", "string"
        attribute "connectionStatus", "string"

        attribute "mode", "string"
        // The wire field the setpoint currently lives in: rh_auto, rh_sleep or
        // fog_level. When this reads fog_level the device is in Manual and
        // humiditySetpoint does not apply; use fogLevel instead.
        attribute "setpointField", "string"
        attribute "humiditySetpoint", "number"
        attribute "fogLevel", "number"

        attribute "humidityRange", "string"    // JSON [min, max] from config
        attribute "fogLevelRange", "string"    // JSON [min, max] from config

        attribute "filterTime", "number"       // raw; units unconfirmed
        attribute "workTime", "number"         // raw; units unconfirmed
        attribute "ledLevel", "string"

        attribute "panelSound", "string"
        attribute "display", "string"
        attribute "dryMode", "string"
        attribute "childLock", "string"
        attribute "adaptiveDisplay", "string"

        command "togglePower"
        command "setMode", [[name: "mode*", type: "ENUM", constraints: modeConstraints()]]
        command "setHumiditySetpoint", [[name: "humidity*", type: "NUMBER", description: "Target relative humidity, %"]]
        command "setFogLevel", [[name: "level*", type: "NUMBER", description: "Mist output level"]]
        command "setCalibration", [[name: "offset*", type: "NUMBER", description: "Humidity sensor offset"]]
        command "setPanelSound", [[name: "panelSound*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDisplay", [[name: "display*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDryMode", [[name: "dryMode*", type: "ENUM", constraints: ["on", "off"]]]

        command "logCapabilities"
        command "logRawState"
        command "sendDesired", [[name: "json*", type: "STRING",
                                 description: "Raw payload for probing, e.g. {\"rh_auto\":45}"]]
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def modeConstraints() { return (state?.presetModes ?: ["Manual", "Auto", "Sleep"]) }

// ---------- Lifecycle ----------

def installed() { sendEvent(name: "driverVersion", value: DRIVER_VERSION); runIn(2, "refresh") }

def updated() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (logEnable) runIn(1800, "logsOff")
    runIn(2, "refresh")
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

def configureMeta(Map m) {
    state.model = m.model
    def hc = (m.humidifierConfig instanceof Map) ? m.humidifierConfig : [:]

    // preset_modes and directive_graph may sit under humidity_mode_config or at
    // the top of humidifier_entity_config depending on model. Check both.
    def modeCfg = (hc.humidity_mode_config instanceof Map) ? hc.humidity_mode_config : [:]
    state.presetModes = modeCfg.preset_modes ?: hc.preset_modes ?: []
    state.directiveGraph = (modeCfg.directive_graph instanceof Map) ? modeCfg.directive_graph
                         : ((hc.directive_graph instanceof Map) ? hc.directive_graph : [:])
    state.descriptionLimits = (hc.description_limits instanceof Map) ? hc.description_limits : [:]

    state.humidityRange    = rangeOr(hc.humidity_range, [30, 90])
    state.fogLevelRange    = rangeOr(hc.fog_level_range, [1, 6])
    state.calibrationRange = rangeOr(hc.calibration_range, [-15, 15])

    applyToggleConfig(m.toggles)
    state.sensorConfigRaw = m.sensorConfig

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (m.model) sendEvent(name: "model", value: m.model)
    sendEvent(name: "humidityRange", value: JsonOutput.toJson(state.humidityRange))
    sendEvent(name: "fogLevelRange", value: JsonOutput.toJson(state.fogLevelRange))

    if (!state.directiveGraph) {
        log.warn "Dreo ${m.model}: no directive_graph in config. The setpoint field will be " +
                 "inferred from the mode name against the fields the device actually reports. " +
                 "Please run logCapabilities() and report this."
    }
    log.info "Dreo humidifier ${m.model}: modes ${state.presetModes}, humidity ${state.humidityRange}, " +
             "fog ${state.fogLevelRange}, graph ${state.directiveGraph}, toggles ${state.toggleMap}"
}

private List rangeOr(Object r, List dflt) {
    return (r instanceof List && r.size() >= 2) ? [r[0] as Integer, r[1] as Integer] : dflt
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
    state.toggleMap = map
    state.toggleLabels = labels
}

// ---------- Directive graph ----------

private Map graphFor(String mode) {
    def g = (state.directiveGraph ?: [:])[mode]
    return (g instanceof Map) ? g : [:]
}

// The wire field holding the setpoint in the given mode.
private String setpointFieldFor(String mode) {
    def n = graphFor(mode)?.name
    if (n) return n.toString()
    if (state.directiveGraph) return null      // graph exists but has no entry: trust it

    // No graph published at all. Every payload seen so far names the per-mode
    // setpoint rh_<mode>, so derive it, but only accept a name the device
    // actually reports, so this can't invent a field.
    def raw = state.raw ?: [:]
    def guess = "rh_${mode?.toLowerCase()}".toString()
    if (raw.containsKey(guess)) {
        if (!state.warnedNoGraph) {
            log.warn "This model publishes no directive_graph; inferring the setpoint field " +
                     "from the mode name (${mode} -> ${guess}). Please run logCapabilities() and report it."
            state.warnedNoGraph = true
        }
        return guess
    }
    if (raw.containsKey("fog_level")) return "fog_level"
    return null
}

private List outOfServiceFor(String mode) {
    def o = graphFor(mode)?.out_of_services
    return (o instanceof List) ? o.collect { it.toString() } : []
}

// The config can declare a command disabled in particular modes.
private boolean disabledInMode(String command, String mode) {
    def lim = (state.descriptionLimits ?: [:])[command]
    def modes = (lim instanceof Map) ? lim.disableOnModes : null
    return (modes instanceof List) && modes.any { it?.toString() == mode }
}

// ---------- Refresh ----------

def refresh() { parent.deviceRefresh(device) }

def applyState(Map props) {
    if (props == null) return
    state.raw = props

    def poweredOn = (props.power_switch != null) ? (props.power_switch as boolean) : null
    if (poweredOn != null) sendEvent(name: "switch", value: poweredOn ? "on" : "off")
    sendEvent(name: "connectionStatus", value: (props.connected == false) ? "offline" : "connected")

    def mode = props.mode?.toString()
    if (mode) sendEvent(name: "mode", value: mode)

    if (props.humidity_sensor != null) {
        sendEvent(name: "humidity", value: (props.humidity_sensor as BigDecimal), unit: "%")
    }
    if (props.fog_level != null) sendEvent(name: "fogLevel", value: (props.fog_level as Integer))
    if (props.filter_time != null) sendEvent(name: "filterTime", value: (props.filter_time as Integer))
    if (props.work_time != null)   sendEvent(name: "workTime", value: (props.work_time as Integer))
    if (props.ledlevel != null)    sendEvent(name: "ledLevel", value: props.ledlevel.toString())

    // Resolve the setpoint through the graph. Both rh_auto and rh_sleep are
    // always present, so taking either without checking the mode is wrong.
    def field = mode ? setpointFieldFor(mode) : null
    if (field) {
        sendEvent(name: "setpointField", value: field)
        if (field != "fog_level" && props[field] != null) {
            sendEvent(name: "humiditySetpoint", value: (props[field] as Integer), unit: "%")
        }
    } else if (mode) {
        sendEvent(name: "setpointField", value: "unknown")
    }

    (state.toggleMap ?: [:]).each { f, attr ->
        if (props.containsKey(f)) sendEvent(name: attr, value: (props[f] as boolean) ? "on" : "off")
    }

    if (logEnable) log.debug "Dreo humidifier ${device.displayName} state: ${props}"
}

// ---------- Control ----------

private Map sendControl(Map desired) {
    def sn = getDataValue("deviceSn")
    if (!sn) { log.warn "Dreo child has no deviceSn. Re-run discovery in the Dreo Integration app"; return [ok: false] }
    def r = parent.deviceControl(sn, desired)
    if (r?.ok) runIn(3, "refresh")
    return r
}

def on()  { def r = sendControl([power_switch: true]);  if (r?.ok) sendEvent(name: "switch", value: "on") }
def off() { def r = sendControl([power_switch: false]); if (r?.ok) sendEvent(name: "switch", value: "off") }
def togglePower() { (device.currentValue("switch") == "on") ? off() : on() }

def setMode(mode) {
    def valid = state.presetModes
    if (valid && !(mode in valid)) { log.warn "Mode '${mode}' not in ${valid}"; return }
    def desired = [mode: mode]
    if (device.currentValue("switch") != "on") desired["power_switch"] = true
    def r = sendControl(desired)
    if (r?.ok) sendEvent(name: "mode", value: mode)
}

def setHumiditySetpoint(humidity) {
    def mode = device.currentValue("mode")
    if (!mode) { log.warn "Mode unknown. Tap Refresh first"; return }

    def field = setpointFieldFor(mode)
    if (!field) {
        log.warn "No setpoint field defined for mode '${mode}' in the device's directive_graph (${state.directiveGraph})"
        return
    }
    if (field == "fog_level" || disabledInMode("set_humidity", mode)) {
        log.warn "In ${mode} mode this humidifier takes a mist level, not a humidity target. " +
                 "Use setFogLevel, or switch to a mode with a humidity setpoint (${autoModes()})."
        return
    }

    def lo = state.humidityRange[0] as Integer
    def hi = state.humidityRange[1] as Integer
    def v = humidity as Integer
    if (v < lo || v > hi) { log.warn "Humidity ${v}% is outside this model's range ${lo}-${hi}%; clamping"; v = Math.max(lo, Math.min(hi, v)) }

    def desired = [(field): v]
    if (device.currentValue("switch") != "on") desired["power_switch"] = true
    def r = sendControl(desired)
    if (r?.ok) {
        sendEvent(name: "humiditySetpoint", value: v, unit: "%")
        log.info "Set ${field} = ${v}% (${mode} mode)"
    }
}

// Modes whose graph entry writes a humidity field rather than fog level.
private List autoModes() {
    return (state.presetModes ?: []).findAll { m -> setpointFieldFor(m.toString()) ==~ /rh_.*/ }
}

def setFogLevel(level) {
    // Not every model has a mist level. Refuse rather than send a field the
    // device does not have.
    if (state.raw && !state.raw.containsKey("fog_level")) {
        log.warn "This device reports no fog_level field, so mist level cannot be set. " +
                 "Please run logCapabilities() and report it."
        return
    }
    def lo = state.fogLevelRange[0] as Integer
    def hi = state.fogLevelRange[1] as Integer
    def v = Math.max(lo, Math.min(hi, level as Integer))
    if (v != (level as Integer)) log.warn "Fog level ${level} outside ${lo}-${hi}; clamped to ${v}"

    def mode = device.currentValue("mode")
    if (mode && ("fog_level" in outOfServiceFor(mode))) {
        log.warn "fog_level is out of service in ${mode} mode per the device's directive_graph. " +
                 "Sending anyway; switch to Manual if it is ignored."
    }
    def desired = [fog_level: v]
    if (device.currentValue("switch") != "on") desired["power_switch"] = true
    def r = sendControl(desired)
    if (r?.ok) sendEvent(name: "fogLevel", value: v)
}

// Humidity sensor offset. Field name is rhoffset in observed payloads.
def setCalibration(offset) {
    def lo = state.calibrationRange[0] as Integer
    def hi = state.calibrationRange[1] as Integer
    def v = Math.max(lo, Math.min(hi, offset as Integer))
    if (v != (offset as Integer)) log.warn "Calibration ${offset} outside ${lo}..${hi}; clamped to ${v}"
    sendControl([rhoffset: v])
}

private setToggleField(String attr, String value) {
    def f = (state.toggleMap ?: [:]).find { k, v -> v == attr }?.key
    if (!f) { log.warn "No '${attr}' toggle on this device (map: ${state.toggleMap})"; return }
    def r = sendControl([(f): (value == "on")])
    if (r?.ok) sendEvent(name: attr, value: value)
    else log.warn "Set ${attr} (${f}) = ${value} failed: status=${r?.status} data=${r?.data}"
}

def setPanelSound(value) { setToggleField("panelSound", value) }
def setDisplay(value)    { setToggleField("display", value) }
def setDryMode(value)    { setToggleField("dryMode", value) }

// ---------- Diagnostics ----------

def sendDesired(String json) {
    if (!json?.trim()) { log.warn "sendDesired: nothing to send"; return }
    def desired
    try { desired = new JsonSlurper().parseText(json) }
    catch (e) { log.error "sendDesired: could not parse JSON: ${e.message}"; return }
    if (!(desired instanceof Map)) { log.error "sendDesired: payload must be a JSON object"; return }
    def r = sendControl(desired)
    if (r?.ok) log.info "sendDesired ${JsonOutput.toJson(desired)} accepted"
    else       log.warn "sendDesired ${JsonOutput.toJson(desired)} rejected: status=${r?.status} data=${r?.data}"
}

def logRawState() {
    if (!state.raw) { log.info "No state captured yet. Tap Refresh first."; return }
    log.info "Dreo ${device.displayName} raw state:\n" + JsonOutput.prettyPrint(JsonOutput.toJson(state.raw))
}

def logCapabilities() {
    log.info "Dreo ${device.displayName} capabilities:\n" + JsonOutput.prettyPrint(JsonOutput.toJson([
        model: state.model, presetModes: state.presetModes,
        directiveGraph: state.directiveGraph, descriptionLimits: state.descriptionLimits,
        humidityRange: state.humidityRange, fogLevelRange: state.fogLevelRange,
        sensorConfigRaw: state.sensorConfigRaw,
        calibrationRange: state.calibrationRange,
        toggles: state.toggleMap, toggleLabels: state.toggleLabels
    ]))
}
