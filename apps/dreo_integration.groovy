/**
 * Dreo Integration - Hubitat parent app  (v0.2.0)
 *
 * Shared account/auth/token lifecycle, device discovery, child creation, and the
 * single poll loop for all Dreo devices. Child drivers hold per-device semantics
 * and delegate HTTP back here (deviceControl / deviceRefresh). Transport matches
 * pydreo-cloud 1.0.0. See README for install steps and adding new device types.
 *
 * v0.2.0 adds a Diagnostics section for remote beta testing: sanitised device
 * dumps, per-device state capture with snapshot/diff, a raw command console, and
 * an optional generic-driver fallback for device types with no purpose-built
 * driver yet.
 *
 * Credit: ported from the official Dreo Home Assistant integration
 * (dreo-team/hass-dreoverse, MIT) and its pydreo-cloud transport library.
 * https://github.com/dreo-team/hass-dreoverse
 * Independent Hubitat port; not affiliated with or endorsed by Dreo.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_VERSION            = "0.2.0"
@Field static final String DEFAULT_CLIENT_ID      = "89ef537b2202481aaaf9077068bcb0c9"
@Field static final String DEFAULT_CLIENT_SECRET  = "41b20a1f60e9499e89c8646c31f93ea1"
@Field static final String NA_BASE_URL            = "https://open-api-us.dreo-tech.com"
@Field static final String EU_BASE_URL            = "https://open-api-eu.dreo-tech.com"
@Field static final String USER_AGENT             = "openapi/1.0.0"
@Field static final String API_VERSION            = "1.0.0"
@Field static final String CHILD_NAMESPACE        = "community"

// Dreo API deviceType -> Hubitat child driver. Add a driver + one entry to
// support a new type (e.g. "ceiling_fan": "Dreo Ceiling Fan"). See README taxonomy.
@Field static final Map DRIVER_FOR_TYPE = [
    "fan"                 : "Dreo Fan",
    "circulation_fan"     : "Dreo Fan",
    "ceiling_fan"         : "Dreo Fan",
    "rgblight_ceiling_fan": "Dreo Fan",
    "hap"                 : "Dreo Fan",
    "hec"                 : "Dreo Fan",         // evaporative cooler: a fan with a
                                                // water tank. Speed, modes and
                                                // oscillation work; humidity does
                                                // not yet. Probe it with sendDesired.
    "humidifier"          : "Dreo Humidifier"
    // NB: "dehumidifier" is deliberately absent. Upstream shares one platform
    // for both because Home Assistant has a device_class to say which direction
    // the thing runs; Hubitat does not, so the driver name is the only signal a
    // user gets and "Dreo Humidifier" on a dehumidifier is simply wrong. The
    // The setpoint machinery does transfer if anyone ever needs it (same config
    // block, same directive_graph), but a dehumidifier has wind_level not
    // fog_level and filter_threshold not filter_time, and its setpoint is a
    // ceiling rather than a floor. Falls through to Dreo Basic Device for now.
]

// Anything not named above that still advertises fan support gets the default
// driver, which is entirely config-driven and so should cope with an unseen type.
@Field static final String DEFAULT_DRIVER = "Dreo Fan"

// Opt-in, for types the default driver can't model (humidifiers, air
// conditioners). Declares no FanControl, so it doesn't misrepresent them.
@Field static final String BASIC_DRIVER = "Dreo Basic Device"

// Dumps go to File Manager in full; only a preview is held in state, which has
// a size ceiling and is not a good place for 20KB of JSON.
@Field static final String  DUMP_FILE     = "dreo-dump.txt"
@Field static final Integer PREVIEW_LIMIT = 20000

definition(
    name: "Dreo Integration",
    namespace: "community",
    author: "classic-dave",
    description: "Cloud integration for Dreo devices (fans, expandable to other types).",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "", iconX2Url: ""
)

preferences { page(name: "mainPage") }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Dreo Integration", install: true, uninstall: true) {
        section("Dreo account") {
            input name: "email", type: "text", title: "Dreo account email", required: true, submitOnChange: true
            input name: "password", type: "password", title: "Dreo account password", required: true, submitOnChange: true
        }
        section("Find devices") {
            input name: "btnDiscover", type: "button", title: "Log in & find devices"
            paragraph statusLine()
        }
        section("Devices to control") {
            if (state.available) {
                input name: "selectedDevices", type: "enum",
                      title: "Select which devices to add (deselect one to remove it)",
                      options: state.available, multiple: true, required: false
                paragraph "Your selection is applied when you tap Done."
            } else {
                paragraph "Tap \"Log in & find devices\" to list the Dreo devices on your account."
            }
        }
        section("Options") {
            input name: "pollInterval", type: "enum", title: "Polling interval",
                  options: ["1 minute", "5 minutes", "10 minutes", "30 minutes"],
                  defaultValue: "5 minutes"
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        }
        section("Advanced", hideable: true, hidden: true) {
            input name: "baseOverride", type: "text", title: "Base URL override (leave blank for default)", required: false
        }
        diagnosticsSection()
        section("Currently added") {
            paragraph childListText()
        }
        section {
            paragraph "<small>App version ${APP_VERSION}</small>"
        }
    }
}

// ---------- Diagnostics UI (beta testing) ----------

private diagnosticsSection() {
    section("Diagnostics", hideable: true, hidden: true) {
        paragraph "Tools for capturing what your devices report, for adding support " +
                  "for models nobody has. Safe to ignore if everything is working."

        input name: "allowBasic", type: "bool", submitOnChange: true,
              title: "Add non-fan device types using the basic driver",
              description: "Humidifiers and air conditioners get on/off, mode and probing. Fans, circulators and purifiers are handled by the default driver already.",
              defaultValue: false
        input name: "redactNames", type: "bool",
              title: "Redact device names in dumps",
              description: "Names like \"Master Bedroom\" describe your home. Serial numbers are always redacted.",
              defaultValue: false

        input name: "btnDumpList", type: "button", title: "Capture device list"

        if (state.available) {
            input name: "dumpDevice", type: "enum", title: "Device for the buttons below",
                  options: state.available, multiple: false, required: false, submitOnChange: true
            input name: "btnDumpState", type: "button", title: "Capture device state (also saves a baseline for Diff)"
            input name: "btnDiff", type: "button", title: "Diff vs last capture"
        } else {
            paragraph "Capture the device list first to enable the per-device tools."
        }

        if (state.dumpText) {
            paragraph dumpBox(state.dumpText)
            paragraph state.dumpFileOk \
                ? "<small>Also written to Settings &rarr; File Manager as <b>${DUMP_FILE}</b>.</small>" \
                : "<small>Could not write to File Manager; copy from the box above.</small>"
        }
    }

    section("Developer", hideable: true, hidden: true) {
        paragraph "Sends a raw payload to the selected device above. Dreo's cloud validates " +
                  "commands server-side and rejects ones it doesn't accept, but this has no " +
                  "guard rails of its own. Don't use it on a device where unexpected " +
                  "behaviour would be a problem."
        input name: "rawDesired", type: "text", required: false,
              title: "Desired payload (JSON object)",
              description: "{\"oscmode\":\"Horizontal\"}"
        input name: "btnSendRaw", type: "button", title: "Send raw command"
    }
}

private String dumpBox(String s) {
    def esc = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return "<textarea rows='24' readonly style='width:100%;font-family:monospace;font-size:11px'>${esc}</textarea>"
}

private String statusLine() {
    def s = state.connectionStatus ?: "not connected"
    def n = state.available ? state.available.size() : 0
    return "Status: ${s}${state.available ? ", ${n} device(s) found" : ""}."
}

private String childListText() {
    def kids = getChildDevices()
    if (!kids) return "No devices added yet."
    return kids.collect { "• ${it.displayName} (${it.getDataValue('deviceSn')})" }.join("\n")
}

// ---------- Lifecycle ----------

def installed()  { configure() }
def updated()    { configure() }
def initialize() { configure() }

private configure() {
    unschedule()
    if (logEnable) runIn(1800, "logsOff")
    login()
    syncChildren()     // create newly-selected, remove deselected
    schedulePolling()
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnDiscover":
            login(); discoverList(); break
        case "btnDumpList":
            login(); discoverList(); break
        case "btnDumpState":
            captureState(settings.dumpDevice as String); break
        case "btnDiff":
            diffState(settings.dumpDevice as String); break
        case "btnSendRaw":
            sendRaw(settings.dumpDevice as String, settings.rawDesired as String); break
        default:
            log.warn "Dreo: unhandled button ${btn}"
    }
}

def logsOff() { app.updateSetting("logEnable", [value: "false", type: "bool"]) }

// ---------- Polling (single shared loop) ----------

def schedulePolling() {
    unschedule("pollAll")
    switch (settings.pollInterval ?: "5 minutes") {
        case "1 minute":   runEvery1Minute("pollAll");   break
        case "5 minutes":  runEvery5Minutes("pollAll");  break
        case "10 minutes": runEvery10Minutes("pollAll"); break
        default:           runEvery30Minutes("pollAll"); break
    }
    if (logEnable) log.debug "Dreo: polling every ${settings.pollInterval ?: '5 minutes'}"
}

def pollAll() { getChildDevices().each { deviceRefresh(it) } }

// ---------- Discovery (find only) + child reconciliation (on save) ----------

// Fetch the account's devices and record supported ones for the multi-select.
// Does NOT create child devices. Also builds the sanitised diagnostic dump.
def discoverList() {
    def r = authedGet("/api/device/list")
    if (logEnable) log.debug "Dreo device list: ${r.data}"
    if (!r.ok) {
        log.warn "Dreo device list failed: status=${r.status}, body=${r.data}"
        setDump("device list (failed)", [status: r.status, body: r.data])
        return
    }

    def list = (r.data?.data instanceof List) ? r.data.data : []
    if (!list) {
        log.warn "Dreo device list empty or unexpected: ${r.data?.data}"
        setDump("device list (empty)", r.data)
        return
    }

    buildAliases(list)

    def available = [:]   // sn -> "Name (model)" for the multi-select
    def meta = [:]        // sn -> data needed to create + configure the child
    def skipped = []
    list.each { dev ->
        def sn = dev.deviceSn
        if (!sn) return
        def nm   = dev.deviceName ?: sn        // ?: sn is a null-label guard, not shape uncertainty
        def mdl  = dev.model
        def type = (dev.deviceType ?: "fan").toString()

        def driverName = DRIVER_FOR_TYPE[type]
        def note = null
        if (!driverName && advertisesFan(dev)) {
            driverName = DEFAULT_DRIVER
            note = "default"
        }
        if (!driverName && settings.allowBasic) {
            driverName = BASIC_DRIVER
            note = "basic"
        }
        if (!driverName) {
            skipped << "${nm} (${type})".toString()
            log.info "Dreo: skipping ${nm} (${sn}): deviceType '${type}' has no driver yet"
            return
        }
        if (note == "default") {
            log.info "Dreo: ${nm} (${sn}) deviceType '${type}' is unknown but advertises fan support; using ${DEFAULT_DRIVER}"
        } else if (note == "basic") {
            log.info "Dreo: ${nm} (${sn}) deviceType '${type}' has no driver; using ${BASIC_DRIVER}"
        }

        def fanCfg  = dev.config?.fan_entity_config ?: [:]
        def range   = (fanCfg.speed_range instanceof List) ? fanCfg.speed_range : null
        def st      = (dev.state instanceof Map) ? dev.state : [:]
        // oscillate on all known tower fans; oscmode kept for other models/types.
        // NB: on circulation_fan, oscmode is an enum (Fixed/Horizontal/Vertical/
        // Both/Pan-tilt), not a boolean; see select_entity_config.
        def oscKey  = (st.containsKey("oscmode") && !st.containsKey("oscillate")) ? "oscmode" : "oscillate"

        // Forward the API's toggle map (config key -> wire field + label) as-is;
        // the driver decides which semantic attribute each one drives.
        def toggles = []
        (dev.config?.toggle_entity_config ?: [:]).each { cfgKey, cfg ->
            if (cfg?.field) toggles << [key: cfgKey.toString(), field: cfg.field.toString(),
                                        label: (cfg.labelName ?: cfgKey).toString(),
                                        operableWhenOff: (cfg.operableWhenOff == true)]
        }

        def label = mdl ? "${nm} (${mdl})".toString() : nm.toString()
        available[sn] = note ? "${label} [${note}]".toString() : label
        meta[sn] = [name: nm, model: mdl, driver: driverName, deviceType: type, note: note,
                    speedMin: range ? (range.min() as Integer) : 1,
                    speedMax: range ? (range.max() as Integer) : null,
                    oscKey: oscKey, presetModes: (fanCfg.preset_modes ?: []), toggles: toggles,
                    fanConfig: fanCfg,
                    humidifierConfig: dev.config?.humidifier_entity_config ?: [:],
                    sensorConfig: dev.config?.sensor_entity_config ?: [:],
                    selectConfig: (dev.config?.select_entity_config instanceof List) ? dev.config.select_entity_config : [],
                    lightConfig: dev.config?.light_entity_config ?: [:],
                    entitySupports: dev.config?.entitySupports ?: []]
    }
    state.available = available
    state.availableMeta = meta

    setDump("device list", r.data)

    log.info "Dreo: found ${available.size()} supported device(s)" +
             (skipped ? ", skipped ${skipped.size()} (${skipped.join(', ')})" : "") +
             ". Select which to add, then tap Done."
}

// Reconcile children against the user's selection: create newly-selected, remove
// deselected. Called on save (updated()).
def syncChildren() {
    def selected = (settings.selectedDevices ?: []) as List
    def meta = state.availableMeta ?: [:]

    selected.each { sn ->
        def m = meta[sn]
        if (!m) { log.warn "Dreo: no metadata for ${sn}; tap \"Log in & find devices\" again"; return }
        def dni = childDni(sn)
        def child = getChildDevice(dni)
        if (!child) {
            try {
                // Hubitat convention: the device's own name goes in Name, and Label
                // is left empty for the user. displayName falls back to Name, so the
                // device reads sensibly out of the box while staying renameable in
                // Hubitat without touching the Dreo app.
                child = addChildDevice(CHILD_NAMESPACE, m.driver, dni,
                                       [name: m.name, isComponent: false])
                child.updateDataValue("deviceSn", sn)
                child.updateDataValue("deviceType", (m.deviceType ?: "").toString())
                child.updateDataValue("model", (m.model ?: "").toString())
                log.info "Dreo: added ${m.driver} '${m.name}' (${sn})"
            } catch (e) {
                log.error "Dreo: failed to create child for ${m.name} (${sn}): ${e}. Is the '${m.driver}' driver installed?"
                return
            }
        }
        syncNaming(child, m.name)
        child.configureMeta([name: m.name, model: m.model, deviceType: m.deviceType,
                             speedMin: m.speedMin, speedMax: m.speedMax, oscKey: m.oscKey,
                             presetModes: m.presetModes, toggles: m.toggles,
                             fanConfig: m.fanConfig, selectConfig: m.selectConfig,
                             humidifierConfig: m.humidifierConfig, sensorConfig: m.sensorConfig,
                             lightConfig: m.lightConfig, entitySupports: m.entitySupports])
        deviceRefresh(child)
    }

    getChildDevices().each { cd ->
        def sn = cd.getDataValue("deviceSn")
        if (!(sn in selected)) {
            log.info "Dreo: removing ${cd.displayName} (${sn}), deselected"
            deleteChildDevice(cd.deviceNetworkId)
        }
    }
}

private String childDni(String sn) { "dreo-${sn}" }

// Keep Name tracking the Dreo app, and leave Label alone so a name set in
// Hubitat is never overwritten.
private void syncNaming(child, String dreoName) {
    if (!child || !dreoName) return
    try {
        if (child.getName() != dreoName) child.setName(dreoName)

        // One-time migration. Up to 0.2.0 the Dreo name was written to Label,
        // which left Name showing the driver name and made clearing the label
        // useless. Only clear it when it still matches what we wrote, so a label
        // the user chose themselves is never touched.
        if (child.getLabel() == dreoName) {
            child.setLabel(null)
            log.info "Dreo: moved '${dreoName}' from Label to Name. You can now set your own " +
                     "label in Hubitat without renaming the device in the Dreo app."
        }
    } catch (e) {
        log.warn "Dreo: could not update naming for '${dreoName}': ${e}"
    }
}

// True when a device presents itself as a fan, whether or not its deviceType is
// one we know. Humidifiers advertise [humidifier, select, switch, number, sensor]
// and carry humidifier_entity_config, so they correctly fail this test.
private boolean advertisesFan(Map dev) {
    def cfg = dev?.config
    if (!(cfg instanceof Map)) return false

    // Climate devices read fan_entity_config for their internal fan speed but
    // are not fans; the fan is subordinate to temperature control, and routing
    // one here would advertise it to Google/Alexa as a fan with no thermostat.
    if (cfg.heater_entity_config instanceof Map && !cfg.heater_entity_config.isEmpty()) return false
    if (cfg.hvac_modes != null || cfg.temperature_range != null) return false

    def supports = cfg.entitySupports
    if (supports instanceof List && !supports.isEmpty()) {
        def s = supports.collect { it?.toString()?.toLowerCase() }
        if (s.contains("climate") || s.contains("water_heater")) return false
        // A device can legitimately be both. An evaporative cooler is a fan with
        // a water tank and really does have speed and oscillation, so a "fan"
        // claim wins even alongside "humidifier".
        return s.contains("fan")
    }

    // No capability list published. A humidifier config with no fan claim
    // belongs to the humidifier driver.
    if (cfg.humidifier_entity_config instanceof Map && !cfg.humidifier_entity_config.isEmpty()) return false
    def fanCfg = cfg.fan_entity_config
    return (fanCfg instanceof Map) && !fanCfg.isEmpty()
}

// ---------- Child-facing API (called by child drivers via parent.*) ----------

// Send a control payload for a device serial. Returns [ok, status, data].
Map deviceControl(String sn, Map desired) {
    if (!sn) { log.warn "Dreo control ignored: child has no deviceSn"; return [ok: false] }
    return authedPost("/api/device/control", [devicesn: sn, desired: desired])
}

// Fetch a device's live property map, or null on failure.
Map fetchStateFor(String sn) {
    if (!sn) return null
    def r = authedGet("/api/device/state", [deviceSn: sn])
    if (!r.ok) { log.warn "Dreo state fetch failed for ${sn}: status=${r.status}, body=${r.data}"; return null }
    def props = r.data?.data
    if (!(props instanceof Map)) { log.warn "Dreo state unexpected shape for ${sn}: ${props}"; return null }
    return props
}

// Fetch a device's live state and hand the raw property map to the child to map.
def deviceRefresh(cd) {
    // Re-resolve to an app-owned wrapper. A wrapper passed up from the child
    // (parent.deviceRefresh(device)) can run built-in methods but not the
    // driver's custom applyState(); getChildDevice() returns one that can.
    def dev = cd ? (getChildDevice(cd.deviceNetworkId) ?: cd) : null
    def sn = dev?.getDataValue("deviceSn")
    if (!sn) { log.warn "Dreo refresh ignored: child has no deviceSn"; return }
    def props = fetchStateFor(sn)
    if (props == null) return
    if (logEnable) log.debug "Dreo state raw (${sn}): ${props}"
    dev.applyState(props)
}

// ---------- Diagnostics ----------

// Stable pseudonyms so the list dump and state dumps still cross-reference.
private void buildAliases(List list) {
    def snMap = [:]
    def nmMap = [:]
    int i = 1
    list.each { d ->
        def sn = d?.deviceSn?.toString()
        def nm = d?.deviceName?.toString()
        if (sn && !snMap.containsKey(sn)) snMap[sn] = "SN-${i}".toString()
        if (nm && !nmMap.containsKey(nm)) nmMap[nm] = "DEVICE-${i}".toString()
        i++
    }
    state.snAlias = snMap
    state.nameAlias = nmMap
}

private String sanitize(String text) {
    if (text == null) return ""
    def out = text
    (state.snAlias ?: [:]).each { real, alias -> out = out.replace(real.toString(), alias.toString()) }
    if (settings.redactNames) {
        // Longest first: a device named "Fan" must not clobber "Fan Corner".
        (state.nameAlias ?: [:]).sort { -it.key.toString().length() }.each { real, alias ->
            out = out.replace(real.toString(), alias.toString())
        }
    }
    return out
}

private String dumpHeader(String what) {
    def when = new Date().format("yyyy-MM-dd HH:mm:ss z", location.timeZone)
    def hub  = location.hub?.firmwareVersionString ?: "unknown"
    return "Dreo Integration diagnostic dump\n" +
           "what:           ${what}\n" +
           "app version:    ${APP_VERSION}\n" +
           "hub platform:   ${hub}\n" +
           "captured:       ${when}\n" +
           "names redacted: ${settings.redactNames ? 'yes' : 'no'}\n" +
           "----------------------------------------------------------------\n"
}

private void setDump(String what, Object payload) {
    def body
    if (payload instanceof String) {
        body = payload
    } else {
        try { body = JsonOutput.prettyPrint(JsonOutput.toJson(payload)) }
        catch (e) { body = payload.toString() }
    }
    def full = dumpHeader(what) + sanitize(body)
    writeDumpFile(full)
    state.dumpText = (full.length() > PREVIEW_LIMIT) \
        ? full.take(PREVIEW_LIMIT) + "\n\n[preview truncated, full dump in File Manager as ${DUMP_FILE}]" \
        : full
}

private void writeDumpFile(String text) {
    try {
        uploadHubFile(DUMP_FILE, text.getBytes("UTF-8"))
        state.dumpFileOk = true
        log.info "Dreo: wrote ${DUMP_FILE} to File Manager (${text.length()} chars)"
    } catch (e) {
        state.dumpFileOk = false
        log.warn "Dreo: could not write ${DUMP_FILE} to File Manager: ${e}"
    }
}

// Capture current state for one device and keep it as the diff baseline.
private void captureState(String sn) {
    if (!sn) { setDump("state capture", "Pick a device first."); return }
    def props = fetchStateFor(sn)
    if (props == null) { setDump("state capture", "No state returned for ${sn}."); return }
    def snaps = state.snapshots ?: [:]
    snaps[sn] = props
    state.snapshots = snaps
    setDump("state capture", [device: sn, state: props])
}

private void diffState(String sn) {
    if (!sn) { setDump("diff", "Pick a device first."); return }
    def before = (state.snapshots ?: [:])[sn]
    if (before == null) { setDump("diff", "No baseline for this device. Tap \"Capture device state\" first."); return }
    def after = fetchStateFor(sn)
    if (after == null) { setDump("diff", "Could not fetch current state for ${sn}."); return }

    def changed = [:]
    (((before.keySet() as Set) + (after.keySet() as Set)) as Set).each { k ->
        if (before[k] != after[k]) changed[k] = [was: before[k], now: after[k]]
    }
    setDump("diff", changed ? [device: sn, changed: changed]
                            : [device: sn, changed: "nothing changed since the baseline"])
}

private void sendRaw(String sn, String json) {
    if (!sn)   { setDump("raw command", "Pick a device in Diagnostics first."); return }
    if (!json) { setDump("raw command", "Enter a JSON payload first."); return }
    def desired
    try { desired = new JsonSlurper().parseText(json) }
    catch (e) { setDump("raw command", "Could not parse JSON: ${e.message}"); return }
    if (!(desired instanceof Map)) {
        setDump("raw command", "Payload must be a JSON object, e.g. {\"speed\":3}")
        return
    }
    def r = deviceControl(sn, desired)
    log.info "Dreo raw command to ${sn}: ${JsonOutput.toJson(desired)} -> ok=${r?.ok} status=${r?.status}"
    setDump("raw command", [device: sn, sent: desired, ok: r?.ok, status: r?.status, response: r?.data])
}

// ---------- Auth + transport (ported from the standalone driver v1.0) ----------

private String loginBaseUrl() { settings.baseOverride ?: NA_BASE_URL }
private String apiBaseUrl()   { settings.baseOverride ?: (state.endpoint ?: NA_BASE_URL) }

def md5(String s) {
    def digest = java.security.MessageDigest.getInstance("MD5")
    digest.update(s.bytes)
    return digest.digest().collect { String.format("%02x", it) }.join()
}

private String regionFromToken(String t) {
    if (!t || !t.contains(":")) return "NA"
    def r = t.split(":", 2)[1].toUpperCase()
    return (r in ["EU", "NA"]) ? r : "NA"
}

private String authHeaderToken() {
    def t = state.accessToken
    return (t == null) ? null : t.split(":", 2)[0]
}

private Map authHeaders() {
    return ["Authorization": "Bearer ${authHeaderToken()}".toString(),
            "Content-Type": "application/json", "UA": USER_AGENT]
}

private Map baseParams() { return [timestamp: now(), pydreover: API_VERSION] }

private void applyToken(String rawToken) {
    state.accessToken = rawToken
    def region = regionFromToken(rawToken)
    state.tokenRegion = region
    state.endpoint = settings.baseOverride ?: ((region == "EU") ? EU_BASE_URL : NA_BASE_URL)
}

def login() { loginAttempt(false) }

private void loginAttempt(boolean isRetry) {
    if (!settings.email || !settings.password) {
        state.connectionStatus = "missing-credentials"
        log.warn "Dreo: enter email and password first"
        return
    }
    def body = [
        client_id: DEFAULT_CLIENT_ID, client_secret: DEFAULT_CLIENT_SECRET,
        grant_type: "openapi", scope: "all",
        email: settings.email, password: md5(settings.password)
    ]
    def params = [
        uri: loginBaseUrl(), path: "/api/oauth/login",
        query: baseParams(), requestContentType: "application/json",
        headers: ["Content-Type": "application/json", "UA": USER_AGENT],
        body: JsonOutput.toJson(body)
    ]
    def ok = false
    try {
        httpPost(params) { resp ->
            if (resp.status == 200 && resp.data?.code == 0) {
                def data = resp.data.data
                def raw = data?.access_token ?: data?.token
                if (raw) {
                    applyToken(raw as String)
                    state.tokenObtainedAt = now()
                    state.connectionStatus = "connected"
                    ok = true
                    if (logEnable) log.debug "Dreo login succeeded (region=${state.tokenRegion}, endpoint=${state.endpoint})"
                } else {
                    log.warn "Dreo login returned no token: ${resp.data}"
                }
            } else {
                log.warn "Dreo login failed: ${resp.data}"
            }
        }
    } catch (e) {
        log.warn "Dreo login exception${isRetry ? ' (retry)' : ''}: ${e}"
    }
    if (!ok) {
        // First attempt can fail transiently (e.g. credentials not yet committed
        // on the very first button tap). Retry once before giving up.
        if (!isRetry) {
            pauseExecution(1000)
            loginAttempt(true)
        } else {
            state.connectionStatus = "auth-error"
            log.warn "Dreo login failed after retry"
        }
    }
}

private void ensureToken() { if (!state.accessToken) login() }

private Integer statusOf(Exception e) {
    try { def sc = e.getStatusCode(); if (sc) return sc as Integer } catch (ignored) {}
    try { def sc = e.response?.status; if (sc) return sc as Integer } catch (ignored) {}
    return null
}

// Single GET/POST path with one-shot re-auth on 401/403. Rebuilds params on each
// send so endpoint + token are re-read after a mid-request re-login.
private Map authedRequest(String method, String path, Map bodyMap, Map extraQuery = [:]) {
    ensureToken()
    def result = [ok: false, status: null, data: null]
    def send = {
        def params = [uri: apiBaseUrl(), path: path,
                      query: baseParams() + (extraQuery ?: [:]), headers: authHeaders()]
        def cb = { resp ->
            result.status = resp.status; result.data = resp.data
            result.ok = (resp.status == 200 && resp.data?.code == 0)
        }
        if (method == "POST") {
            params.requestContentType = "application/json"
            params.body = JsonOutput.toJson(bodyMap)
            httpPost(params, cb)
        } else {
            httpGet(params, cb)
        }
    }
    try {
        send()
    } catch (e) {
        def sc = statusOf(e)
        if (sc in [401, 403]) {
            if (logEnable) log.debug "Dreo ${method} ${path} auth expired (${sc}); re-authenticating"
            state.accessToken = null; login()
            try { send() }
            catch (e2) { log.error "Dreo ${method} ${path} failed after reauth: ${e2}" }
        } else {
            log.error "Dreo ${method} ${path} exception: ${e}"
        }
    }
    return result
}

private Map authedGet(String path, Map extraQuery = [:]) {
    return authedRequest("GET", path, null, extraQuery)
}

private Map authedPost(String path, Map bodyMap, Map extraQuery = [:]) {
    return authedRequest("POST", path, bodyMap, extraQuery)
}
