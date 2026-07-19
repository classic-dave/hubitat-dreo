/**
 * Dreo Integration - Hubitat parent app  (v0.1)
 *
 * Shared account/auth/token lifecycle, device discovery, child creation, and the
 * single poll loop for all Dreo devices. Child drivers hold per-device semantics
 * and delegate HTTP back here (deviceControl / deviceRefresh). Transport matches
 * pydreo-cloud 1.0.0. See README for install steps and adding new device types.
 *
 * Credit: ported from the official Dreo Home Assistant integration
 * (dreo-team/hass-dreoverse, MIT) and its pydreo-cloud transport library.
 * https://github.com/dreo-team/hass-dreoverse
 * Independent Hubitat port; not affiliated with or endorsed by Dreo.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String DEFAULT_CLIENT_ID     = "89ef537b2202481aaaf9077068bcb0c9"
@Field static final String DEFAULT_CLIENT_SECRET  = "41b20a1f60e9499e89c8646c31f93ea1"
@Field static final String NA_BASE_URL            = "https://open-api-us.dreo-tech.com"
@Field static final String EU_BASE_URL            = "https://open-api-eu.dreo-tech.com"
@Field static final String USER_AGENT             = "openapi/1.0.0"
@Field static final String API_VERSION            = "1.0.0"
@Field static final String CHILD_NAMESPACE        = "community"

// Dreo API deviceType -> Hubitat child driver. Add a driver + one entry to
// support a new type (e.g. "hac": "Dreo Air Conditioner"). See README taxonomy.
@Field static final Map DRIVER_FOR_TYPE = ["fan": "Dreo Tower Fan"]

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
        section("Currently added") {
            paragraph childListText()
        }
    }
}

private String statusLine() {
    def s = state.connectionStatus ?: "not connected"
    def n = state.available ? state.available.size() : 0
    return "Status: ${s}${state.available ? " — ${n} device(s) found" : ""}."
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
    if (btn == "btnDiscover") {
        login()
        discoverList()   // populates the multi-select; does not create children
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
// Does NOT create child devices.
def discoverList() {
    def r = authedGet("/api/device/list")
    if (logEnable) log.debug "Dreo device list: ${r.data}"
    if (!r.ok) { log.warn "Dreo device list failed: status=${r.status}, body=${r.data}"; return }

    def list = (r.data?.data instanceof List) ? r.data.data : []
    if (!list) { log.warn "Dreo device list empty or unexpected: ${r.data?.data}"; return }

    def available = [:]   // sn -> "Name (model)" for the multi-select
    def meta = [:]        // sn -> data needed to create + configure the child
    list.each { dev ->
        def sn = dev.deviceSn
        if (!sn) return
        def nm   = dev.deviceName ?: sn        // ?: sn is a null-label guard, not shape uncertainty
        def mdl  = dev.model
        def type = (dev.deviceType ?: "fan").toString()

        def driverName = DRIVER_FOR_TYPE[type]
        if (!driverName) {
            log.info "Dreo: skipping ${nm} (${sn}) — deviceType '${type}' has no driver yet"
            return
        }

        def fanCfg  = dev.config?.fan_entity_config ?: [:]
        def range   = (fanCfg.speed_range instanceof List) ? fanCfg.speed_range : null
        def st      = (dev.state instanceof Map) ? dev.state : [:]
        // oscillate on all known tower fans; oscmode kept for other models/types.
        def oscKey  = (st.containsKey("oscmode") && !st.containsKey("oscillate")) ? "oscmode" : "oscillate"

        // Forward the API's toggle map (config key -> wire field + label) as-is;
        // the driver decides which semantic attribute each one drives.
        def toggles = []
        (dev.config?.toggle_entity_config ?: [:]).each { cfgKey, cfg ->
            if (cfg?.field) toggles << [key: cfgKey.toString(), field: cfg.field.toString(),
                                        label: (cfg.labelName ?: cfgKey).toString()]
        }

        available[sn] = mdl ? "${nm} (${mdl})".toString() : nm.toString()
        meta[sn] = [name: nm, model: mdl, driver: driverName,
                    speedMin: range ? (range.min() as Integer) : 1,
                    speedMax: range ? (range.max() as Integer) : null,
                    oscKey: oscKey, presetModes: (fanCfg.preset_modes ?: []), toggles: toggles]
    }
    state.available = available
    state.availableMeta = meta
    log.info "Dreo: found ${available.size()} supported device(s). Select which to add, then tap Done."
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
                child = addChildDevice(CHILD_NAMESPACE, m.driver, dni,
                                       [name: m.driver, label: m.name, isComponent: false])
                child.updateDataValue("deviceSn", sn)
                log.info "Dreo: added ${m.driver} '${m.name}' (${sn})"
            } catch (e) {
                log.error "Dreo: failed to create child for ${m.name} (${sn}): ${e} — is the '${m.driver}' driver installed?"
                return
            }
        }
        child.configureMeta([name: m.name, model: m.model, speedMin: m.speedMin,
                             speedMax: m.speedMax, oscKey: m.oscKey, presetModes: m.presetModes,
                             toggles: m.toggles])
        deviceRefresh(child)
    }

    getChildDevices().each { cd ->
        def sn = cd.getDataValue("deviceSn")
        if (!(sn in selected)) {
            log.info "Dreo: removing ${cd.displayName} (${sn}) — deselected"
            deleteChildDevice(cd.deviceNetworkId)
        }
    }
}

private String childDni(String sn) { "dreo-${sn}" }

// ---------- Child-facing API (called by child drivers via parent.*) ----------

// Send a control payload for a device serial. Returns [ok, status, data].
Map deviceControl(String sn, Map desired) {
    if (!sn) { log.warn "Dreo control ignored: child has no deviceSn"; return [ok: false] }
    return authedPost("/api/device/control", [devicesn: sn, desired: desired])
}

// Fetch a device's live state and hand the raw property map to the child to map.
def deviceRefresh(cd) {
    // Re-resolve to an app-owned wrapper. A wrapper passed up from the child
    // (parent.deviceRefresh(device)) can run built-in methods but not the
    // driver's custom applyState(); getChildDevice() returns one that can.
    def dev = cd ? (getChildDevice(cd.deviceNetworkId) ?: cd) : null
    def sn = dev?.getDataValue("deviceSn")
    if (!sn) { log.warn "Dreo refresh ignored: child has no deviceSn"; return }
    def r = authedGet("/api/device/state", [deviceSn: sn])
    if (!r.ok) { log.warn "Dreo refresh failed for ${sn}: status=${r.status}, body=${r.data}"; return }
    def props = r.data?.data
    if (!(props instanceof Map)) { log.warn "Dreo state unexpected shape for ${sn}: ${props}"; return }
    if (logEnable) log.debug "Dreo state raw (${sn}): ${props}"
    dev.applyState(props)
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
