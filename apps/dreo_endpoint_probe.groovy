/**
 * Dreo Endpoint Probe - standalone Hubitat app  (0.1.0)
 *
 * Throwaway diagnostic. Calls Dreo's v1 and v2 device-list endpoints with the
 * same token and reports what each one returns, so the two can be compared on a
 * real account without changing the Dreo Integration app.
 *
 *   v1  /api/device/list        ?pydreover=1.0.0   (pydreo-cloud 1.0.0)
 *   v2  /api/v2/device/list     ?dreover=1.0.0     (pydreo-client 2.0.0)
 *
 * Deliberately named and namespaced apart from "Dreo Integration" so both can
 * be installed at once. Read-only: it logs in, does GETs, and writes a report.
 * It never sends a control command and never creates a child device.
 *
 * Serial numbers are always replaced with SN-1, SN-2 and so on. Device names
 * are replaced when "Redact device names" is ticked.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String PROBE_VERSION  = "0.1.0"
@Field static final String CLIENT_ID      = "89ef537b2202481aaaf9077068bcb0c9"
@Field static final String CLIENT_SECRET  = "41b20a1f60e9499e89c8646c31f93ea1"
@Field static final String NA_BASE_URL    = "https://open-api-us.dreo-tech.com"
@Field static final String EU_BASE_URL    = "https://open-api-eu.dreo-tech.com"
@Field static final String USER_AGENT     = "openapi/1.0.0"
@Field static final String API_VERSION    = "1.0.0"
@Field static final String OUT_FILE       = "dreo-endpoint-probe.txt"
@Field static final Integer PREVIEW_LIMIT = 20000

definition(
    name: "Dreo Endpoint Probe",
    namespace: "classic-dave",
    author: "classic-dave",
    description: "Read-only diagnostic: compares Dreo's v1 and v2 cloud endpoints on your account.",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "", iconX2Url: ""
)

preferences { page(name: "mainPage") }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Dreo Endpoint Probe", install: true, uninstall: true) {
        section("Dreo account") {
            input name: "email", type: "text", title: "Dreo account email", required: true, submitOnChange: true
            input name: "password", type: "password", title: "Dreo account password", required: true, submitOnChange: true
            input name: "redactNames", type: "bool", title: "Redact device names in the report (your email is always redacted)", defaultValue: true
        }
        section("Device list") {
            paragraph "Logs in once, then calls both list endpoints with the same token and compares them."
            input name: "btnCompareList", type: "button", title: "Compare v1 and v2 device lists"
        }
        section("Device state") {
            if (state.aliasToSn) {
                input name: "probeDevice", type: "enum", title: "Device",
                      options: (state.aliasToSn.keySet() as List).sort(), multiple: false,
                      required: false, submitOnChange: true
                input name: "btnCompareState", type: "button", title: "Compare v1 and v2 device state"
            } else {
                paragraph "Run the device-list comparison first."
            }
        }
        section("Login") {
            paragraph "Logs in twice, once with each version parameter, to see whether the name matters."
            input name: "btnLoginCheck", type: "button", title: "Compare login with pydreover and dreover"
        }
        section("Advanced", hideable: true, hidden: true) {
            input name: "baseOverride", type: "text", title: "Base URL override (blank for default)", required: false
        }
        if (state.outText) {
            section("Report") {
                paragraph outBox(state.outText)
                paragraph state.outFileOk \
                    ? "<small>Also written to Settings &rarr; File Manager as <b>${OUT_FILE}</b>.</small>" \
                    : "<small>Could not write to File Manager; copy from the box above.</small>"
            }
        }
        section {
            paragraph "<small>Probe version ${PROBE_VERSION}</small>"
        }
    }
}

private String outBox(String s) {
    def esc = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return "<textarea rows='28' readonly style='width:100%;font-family:monospace;font-size:11px'>${esc}</textarea>"
}

def installed() {}
def updated()   {}
def uninstalled() {}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnCompareList":  compareLists();  break
        case "btnCompareState": compareStates(); break
        case "btnLoginCheck":   compareLogins(); break
        default: log.warn "Dreo Probe: unhandled button ${btn}"
    }
}

// ---------- Probes ----------

private void compareLists() {
    // pydreo-client 2.0.0 sends dreover on login as well as on the list, so
    // each path gets a token minted the way its own library mints one.
    def l1 = login("pydreover")
    if (!l1.ok) { report("device list", ["login with pydreover failed: ${l1.detail}"]); return }
    def l2 = login("dreover")
    if (!l2.ok) { report("device list", ["login with dreover failed: ${l2.detail}"]); return }

    def v1 = describeList(l1.endpoint, l1.token, "/api/device/list", "pydreover")
    def v2 = describeList(l2.endpoint, l2.token, "/api/v2/device/list", "dreover")

    buildAliases(v1.serials + v2.serials)

    def lines = []
    lines << "login:   ok, region ${l1.region}, endpoint ${l1.endpoint}"
    lines << ""
    lines += renderList("v1  GET /api/device/list?pydreover=${API_VERSION}", v1)
    lines << ""
    lines += renderList("v2  GET /api/v2/device/list?dreover=${API_VERSION}", v2)
    lines << ""
    lines += renderDiff(v1, v2)

    def map = [:]
    (v1.serials + v2.serials).each { sn -> map[aliasFor(sn)] = sn }
    state.aliasToSn = map

    report("device list, v1 vs v2", lines)
}

private void compareStates() {
    def alias = settings.probeDevice as String
    def sn = (state.aliasToSn ?: [:])[alias]
    if (!sn) { report("device state", ["Pick a device first."]); return }

    def l1 = login("pydreover")
    if (!l1.ok) { report("device state", ["login with pydreover failed: ${l1.detail}"]); return }
    def l2 = login("dreover")
    if (!l2.ok) { report("device state", ["login with dreover failed: ${l2.detail}"]); return }

    def v1 = describeState(l1.endpoint, l1.token, "/api/device/state", "pydreover", sn)
    def v2 = describeState(l2.endpoint, l2.token, "/api/v2/device/state", "dreover", sn)

    def lines = []
    lines << "device:  ${alias}"
    lines << ""
    lines += renderState("v1  GET /api/device/state", v1)
    lines << ""
    lines += renderState("v2  GET /api/v2/device/state", v2)
    lines << ""
    def only1 = (v1.keys ?: []) - (v2.keys ?: [])
    def only2 = (v2.keys ?: []) - (v1.keys ?: [])
    lines << "keys in v1 only:  ${only1 ?: 'none'}"
    lines << "keys in v2 only:  ${only2 ?: 'none'}"

    report("device state, v1 vs v2", lines)
}

private void compareLogins() {
    def lines = []
    ["pydreover", "dreover"].each { vp ->
        def li = login(vp)
        lines << "POST /api/oauth/login?${vp}=${API_VERSION}"
        lines << "  http status:   ${li.status}"
        lines << "  body code:     ${li.code}"
        lines << "  body msg:      ${li.msg}"
        lines << "  token present: ${li.token ? 'yes' : 'no'}"
        lines << "  region:        ${li.region ?: 'n/a'}"
        if (li.detail && !li.ok) lines << "  detail:        ${li.detail}"
        lines << ""
    }
    report("login, pydreover vs dreover", lines)
}

// ---------- Description ----------

// Returns [status, code, msg, shape, count, serials, devices, error]
private Map describeList(String endpoint, String token, String path, String verParam) {
    def r = apiGet(endpoint, path, [(verParam): API_VERSION], token)
    def out = [status: r.status, code: null, msg: null, shape: "none",
               count: 0, serials: [], devices: [], error: r.error]
    def body = r.body
    if (!(body instanceof Map)) {
        out.shape = (body == null) ? "no body" : typeName(body)
        return out
    }
    out.code = body.code
    out.msg  = body.msg
    def data = body.data

    def list = null
    if (data instanceof List) {
        out.shape = "data is a List"
        list = data
    } else if (data instanceof Map) {
        // v2 may wrap the list, e.g. data.list. Record the wrapper keys either way.
        out.shape = "data is a Map with keys ${(data.keySet() as List).sort()}"
        def inner = data.find { k, v -> v instanceof List }
        if (inner) {
            out.shape = out.shape + ", list found under '${inner.key}'"
            list = inner.value
        }
    } else {
        out.shape = (data == null) ? "data is null" : "data is ${typeName(data)}"
    }

    if (list == null) return out
    out.count = list.size()
    list.each { d ->
        if (!(d instanceof Map)) { out.devices << [note: "entry is ${typeName(d)}"]; return }
        def sn = d.deviceSn?.toString()
        if (sn) out.serials << sn
        def cfg = d.config
        out.devices << [
            sn        : sn,
            name      : d.deviceName?.toString(),
            model     : d.model?.toString(),
            deviceType: d.deviceType?.toString(),
            missing   : ["deviceSn", "deviceName", "model", "deviceType", "config"].findAll { !d.containsKey(it) || d[it] == null },
            configKeys: (cfg instanceof Map) ? (cfg.keySet() as List).sort() : ((cfg == null) ? null : typeName(cfg)),
            topKeys   : (d.keySet() as List).sort()
        ]
    }
    return out
}

private Map describeState(String endpoint, String token, String path, String verParam, String sn) {
    def r = apiGet(endpoint, path, [(verParam): API_VERSION, deviceSn: sn], token)
    def out = [status: r.status, code: null, msg: null, shape: "none", keys: [], error: r.error]
    def body = r.body
    if (!(body instanceof Map)) {
        out.shape = (body == null) ? "no body" : typeName(body)
        return out
    }
    out.code = body.code
    out.msg  = body.msg
    def data = body.data
    if (data instanceof Map) {
        out.shape = "data is a Map"
        out.keys = (data.keySet() as List).sort()
    } else {
        out.shape = (data == null) ? "data is null" : "data is ${typeName(data)}"
    }
    return out
}

// ---------- Rendering ----------

private List renderList(String title, Map d) {
    def lines = [title]
    lines << "  http status:  ${d.status}"
    lines << "  body code:    ${d.code}"
    lines << "  body msg:     ${d.msg}"
    lines << "  shape:        ${d.shape}"
    lines << "  device count: ${d.count}"
    if (d.error) lines << "  error:        ${d.error}"
    d.devices.each { dev ->
        if (dev.note) { lines << "  - ${dev.note}"; return }
        def nm = settings.redactNames ? "" : " \"${dev.name}\""
        lines << "  - ${aliasFor(dev.sn)}${nm}  model=${dev.model}  deviceType=${dev.deviceType}"
        lines << "      config keys: ${dev.configKeys}"
        if (dev.missing) lines << "      missing or null: ${dev.missing}"
    }
    return lines
}

private List renderState(String title, Map d) {
    def lines = [title]
    lines << "  http status: ${d.status}"
    lines << "  body code:   ${d.code}"
    lines << "  body msg:    ${d.msg}"
    lines << "  shape:       ${d.shape}"
    lines << "  key count:   ${(d.keys ?: []).size()}"
    lines << "  keys:        ${d.keys}"
    if (d.error) lines << "  error:       ${d.error}"
    return lines
}

private List renderDiff(Map v1, Map v2) {
    def lines = ["comparison"]
    lines << "  count:  v1 ${v1.count}, v2 ${v2.count}"
    def s1 = v1.serials as Set
    def s2 = v2.serials as Set
    def only1 = (s1 - s2).collect { aliasFor(it) }.sort()
    def only2 = (s2 - s1).collect { aliasFor(it) }.sort()
    lines << "  in v1 only: ${only1 ?: 'none'}"
    lines << "  in v2 only: ${only2 ?: 'none'}"

    def m1 = v1.devices.findAll { it.model }.collect { it.model }.sort()
    def m2 = v2.devices.findAll { it.model }.collect { it.model }.sort()
    lines << "  v1 models: ${m1}"
    lines << "  v2 models: ${m2}"

    def k1 = v1.devices.collect { it.topKeys ?: [] }.flatten().unique().sort()
    def k2 = v2.devices.collect { it.topKeys ?: [] }.flatten().unique().sort()
    lines << "  entry keys in v1 only: ${(k1 - k2) ?: 'none'}"
    lines << "  entry keys in v2 only: ${(k2 - k1) ?: 'none'}"
    return lines
}

// ---------- Aliases and output ----------

private void buildAliases(List serials) {
    def map = state.snAlias ?: [:]
    int i = map.size()
    serials.each { sn ->
        def s = sn?.toString()
        if (s && !map.containsKey(s)) { i = i + 1; map[s] = "SN-${i}".toString() }
    }
    state.snAlias = map
}

private String aliasFor(String sn) {
    if (!sn) return "SN-?"
    return (state.snAlias ?: [:])[sn] ?: "SN-?"
}

private String sanitize(String text) {
    def out = text ?: ""
    (state.snAlias ?: [:]).each { real, alias -> out = out.replace(real.toString(), alias.toString()) }
    if (settings.email) out = out.replace(settings.email.toString(), "EMAIL")
    return out
}

private void report(String what, List lines) {
    def when = new Date().format("yyyy-MM-dd HH:mm:ss z", location.timeZone)
    def hub  = location.hub?.firmwareVersionString ?: "unknown"
    def head = "Dreo Endpoint Probe\n" +
               "what:           ${what}\n" +
               "probe version:  ${PROBE_VERSION}\n" +
               "hub platform:   ${hub}\n" +
               "captured:       ${when}\n" +
               "names redacted: ${settings.redactNames ? 'yes' : 'no'}\n" +
               "----------------------------------------------------------------\n"
    def full = sanitize(head + lines.join("\n") + "\n")
    log.info "Dreo Probe:\n${full}"
    writeOutFile(full)
    state.outText = (full.length() > PREVIEW_LIMIT) \
        ? full.take(PREVIEW_LIMIT) + "\n\n[preview truncated, full report in File Manager as ${OUT_FILE}]" \
        : full
}

private void writeOutFile(String text) {
    try {
        uploadHubFile(OUT_FILE, text.getBytes("UTF-8"))
        state.outFileOk = true
    } catch (e) {
        state.outFileOk = false
        log.warn "Dreo Probe: could not write ${OUT_FILE}: ${e}"
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

// ---------- Transport ----------

private String baseUrl() { settings.baseOverride ?: NA_BASE_URL }

private String md5(String s) {
    def digest = java.security.MessageDigest.getInstance("MD5")
    digest.update(s.bytes)
    return digest.digest().collect { String.format("%02x", it) }.join()
}

// Returns [ok, status, code, msg, token, region, endpoint, detail]
private Map login(String verParam) {
    def out = [ok: false, status: null, code: null, msg: null, token: null,
               region: null, endpoint: baseUrl(), detail: null]
    if (!settings.email || !settings.password) { out.detail = "enter email and password first"; return out }

    def params = [
        uri: baseUrl(), path: "/api/oauth/login",
        query: [timestamp: now(), (verParam): API_VERSION],
        requestContentType: "application/json",
        headers: ["Content-Type": "application/json", "UA": USER_AGENT],
        body: JsonOutput.toJson([
            client_id: CLIENT_ID, client_secret: CLIENT_SECRET,
            grant_type: "openapi", scope: "all",
            email: settings.email, password: md5(settings.password as String)
        ])
    ]
    try {
        httpPost(params) { resp ->
            out.status = resp.status
            def body = resp.data
            if (body instanceof Map) {
                out.code = body.code
                out.msg  = body.msg
                def data = body.data
                def raw = (data instanceof Map) ? (data.access_token ?: data.token) : null
                if (raw) {
                    def s = raw.toString()
                    out.token  = s.split(":", 2)[0]
                    out.region = s.contains(":") ? s.split(":", 2)[1].toUpperCase() : "NA"
                    out.endpoint = settings.baseOverride ?: ((out.region == "EU") ? EU_BASE_URL : NA_BASE_URL)
                    out.ok = true
                } else {
                    out.detail = "no token in response"
                }
            } else {
                out.detail = "unexpected body type"
            }
        }
    } catch (e) {
        out.detail = e.toString()
        try { def sc = e.getStatusCode(); if (sc) out.status = sc as Integer } catch (ignored) {}
    }
    return out
}

// Returns [status, body, error]
private Map apiGet(String endpoint, String path, Map extraQuery, String token) {
    def out = [status: null, body: null, error: null]
    def params = [
        uri: endpoint, path: path,
        query: [timestamp: now()] + (extraQuery ?: [:]),
        headers: ["Content-Type": "application/json", "UA": USER_AGENT,
                  "Authorization": "Bearer ${token}".toString()]
    ]
    try {
        httpGet(params) { resp -> out.status = resp.status; out.body = resp.data }
    } catch (e) {
        out.error = e.toString()
        try { def sc = e.getStatusCode(); if (sc) out.status = sc as Integer } catch (ignored) {}
        try { out.body = e.getResponse()?.getData() } catch (ignored) {}
    }
    return out
}
