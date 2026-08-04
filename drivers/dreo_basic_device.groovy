/**
 * Dreo Basic Device - Hubitat driver, child of the Dreo Integration app  (0.2.0)
 *
 * The minimal driver for Dreo devices that are NOT fans: humidifiers, air
 * conditioners, and any future type the default Dreo Fan driver can't model.
 *
 * It exists for one structural reason: Hubitat capabilities are declared
 * statically in metadata, so a driver that declares FanControl declares it for
 * every device using it. Putting a humidifier on Dreo Fan would advertise it as
 * a fan to dashboards, Google Home, Alexa and Rule Machine. This driver declares
 * only Switch, Actuator and Refresh, which is true of every Dreo device.
 *
 * It maps the fields common to all types (power, mode, connectivity, speed
 * where present) and exposes the full live property map, and carries the same
 * sendDesired probe as the default driver. That makes it a usable on/off device
 * today and a probing tool for building a proper driver later.
 *
 * When a purpose-built driver lands for your device type, change this device's
 * Type on its device page and re-save the app so the new driver gets its config.
 * Nothing needs deleting.
 *
 * Credit: ported from the official Dreo Home Assistant integration
 * (dreo-team/hass-dreoverse, MIT) and its pydreo-cloud transport library.
 * https://github.com/dreo-team/hass-dreoverse
 * Independent Hubitat port; not affiliated with or endorsed by Dreo.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.2.0"

// Fields that mean the same thing on every Dreo device type seen so far.
@Field static final String F_POWER     = "power_switch"
@Field static final String F_CONNECTED = "connected"
@Field static final String F_SPEED     = "speed"
@Field static final String F_MODE      = "mode"

metadata {
    definition(name: "Dreo Basic Device", namespace: "community", author: "classic-dave") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"

        attribute "driverVersion", "string"
        attribute "deviceType", "string"
        attribute "model", "string"
        attribute "connectionStatus", "string"
        attribute "speedLevel", "number"
        attribute "mode", "string"
        attribute "lastUpdate", "string"
        attribute "fieldCount", "number"

        command "sendDesired", [[name: "json*", type: "STRING",
                                 description: "Raw desired payload, e.g. {\"oscmode\":\"Horizontal\"}"]]
        command "logRawState"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

// ---------- Lifecycle ----------

def installed() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    runIn(2, "refresh")
}

def updated() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (logEnable) runIn(1800, "logsOff")
    runIn(2, "refresh")
}

def logsOff() { device.updateSetting("logEnable", [value: "false", type: "bool"]) }

// Called by the parent at discovery to hand down per-device metadata.
def configureMeta(Map m) {
    state.deviceType     = m.deviceType
    state.model          = m.model
    state.speedMin       = m.speedMin ?: 1
    state.speedMax       = m.speedMax
    state.presetModes    = m.presetModes ?: []
    state.entitySupports = m.entitySupports ?: []
    state.toggleFields   = (m.toggles ?: []).collect { it.field }

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (m.deviceType) sendEvent(name: "deviceType", value: m.deviceType)
    if (m.model)      sendEvent(name: "model", value: m.model)

    log.info "Dreo basic: ${m.name} is deviceType '${m.deviceType}', model ${m.model}, " +
             "speed ${state.speedMin}-${state.speedMax}, modes ${state.presetModes}"
}

// ---------- Refresh (parent fetches, this maps the result) ----------

def refresh() { parent.deviceRefresh(device) }

// Called by the parent with the device's raw live-state property map.
def applyState(Map props) {
    if (props == null) return

    // The whole point of this driver: keep everything, map only what's universal.
    state.raw = props
    state.rawKeys = (props.keySet() as List).sort()
    sendEvent(name: "fieldCount", value: props.size())
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))

    if (props[F_POWER] != null) {
        sendEvent(name: "switch", value: (props[F_POWER] as boolean) ? "on" : "off")
    }
    if (props[F_SPEED] != null) {
        sendEvent(name: "speedLevel", value: props[F_SPEED])
    }
    if (props[F_MODE] != null) {
        sendEvent(name: "mode", value: props[F_MODE].toString())
    }
    sendEvent(name: "connectionStatus", value: (props[F_CONNECTED] == false) ? "offline" : "connected")

    if (logEnable) log.debug "Dreo basic state (${props.size()} fields): ${props}"
}

// ---------- Control ----------

private Map sendControl(Map desired) {
    def sn = getDataValue("deviceSn")
    if (!sn) {
        log.warn "Dreo child has no deviceSn. Re-run discovery in the Dreo Integration app"
        return [ok: false]
    }
    def r = parent.deviceControl(sn, desired)
    if (r?.ok) runIn(3, "refresh")   // re-read real state; the device may override
    return r
}

def on() {
    def r = sendControl([(F_POWER): true])
    if (r?.ok) sendEvent(name: "switch", value: "on")
}

def off() {
    def r = sendControl([(F_POWER): false])
    if (r?.ok) sendEvent(name: "switch", value: "off")
}

// Probe any field on the device without shipping a new driver.
def sendDesired(String json) {
    if (!json?.trim()) { log.warn "sendDesired: nothing to send"; return }
    def desired
    try {
        desired = new JsonSlurper().parseText(json)
    } catch (e) {
        log.error "sendDesired: could not parse JSON: ${e.message}. Expected something like {\"speed\":3}"
        return
    }
    if (!(desired instanceof Map)) {
        log.error "sendDesired: payload must be a JSON object, e.g. {\"speed\":3}"
        return
    }
    def r = sendControl(desired)
    if (r?.ok) {
        log.info "sendDesired ${JsonOutput.toJson(desired)} accepted"
    } else {
        log.warn "sendDesired ${JsonOutput.toJson(desired)} rejected: status=${r?.status} data=${r?.data}"
    }
}

// Pretty-print the last known property map to the logs, for copying into a report.
def logRawState() {
    if (!state.raw) { log.info "No state captured yet. Tap Refresh first."; return }
    log.info "Dreo basic raw state for ${device.displayName}:\n" +
             JsonOutput.prettyPrint(JsonOutput.toJson(state.raw))
}
