package com.univpn.app.server

import android.text.InputType
import android.util.Log
import com.univpn.app.provider.ProviderRegistry
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import fi.iki.elonen.NanoHTTPD
import java.io.File

class ConfigImportServer(
    port: Int,
    private val onConfigReceived: (name: String, content: String) -> Result<Unit>,
    private val onCredentialsReceived: (providerId: String, username: String, password: String) -> Result<String>
) : NanoHTTPD(port) {

    /** One-time PIN shown on the TV next to the address; every write must send it. */
    private val pinGuard = PinGuard()
    val pin: String get() = pinGuard.pin

    init {
        setAsyncRunner(BoundedRunner(MAX_CONNECTIONS))
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET  && session.uri == "/"            -> serveForm()
        session.method == Method.GET  && session.uri == "/providers"   -> serveProviders()
        session.method == Method.POST && session.uri == "/upload"      -> checkWrite(session) ?: handleUpload(session)
        session.method == Method.POST && session.uri == "/credentials" -> checkWrite(session) ?: handleCredentials(session)
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    /** Rejects a write before its body is read: missing/oversized length or a wrong PIN. */
    private fun checkWrite(session: IHTTPSession): Response? {
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return textResponse(Response.Status.LENGTH_REQUIRED, "Content-Length required")
        if (length > MAX_BODY_BYTES)
            return textResponse(Response.Status.PAYLOAD_TOO_LARGE, "Upload too large (max ${MAX_BODY_BYTES / 1024} KB)")
        return when (pinGuard.check(session.headers[PIN_HEADER])) {
            PinGuard.Result.OK -> null
            PinGuard.Result.WRONG -> {
                Log.w(TAG, "Rejected write with wrong PIN from ${session.remoteIpAddress}")
                textResponse(Response.Status.FORBIDDEN, "Wrong PIN — enter the PIN shown on the TV")
            }
            PinGuard.Result.LOCKED ->
                textResponse(Response.Status.TOO_MANY_REQUESTS, "Too many wrong PINs — wait a minute and try again")
        }
    }

    private fun serveForm(): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE_HTML)

    private fun serveProviders(): Response {
        val json = buildString {
            append("[")
            ProviderRegistry.all.forEachIndexed { i, connector ->
                if (i > 0) append(",")
                append("{")
                append("\"id\":\"${connector.id}\",")
                append("\"name\":\"${connector.displayName}\",")
                append("\"fields\":[")
                connector.credentialFields.forEachIndexed { fi, field ->
                    if (fi > 0) append(",")
                    val htmlType = when {
                        field.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 -> "password"
                        else -> "text"
                    }
                    append("{")
                    append("\"key\":\"${field.key}\",")
                    append("\"label\":\"${field.label}\",")
                    append("\"type\":\"$htmlType\",")
                    append("\"hint\":\"${field.hint}\"")
                    append("}")
                }
                append("]}")
            }
            append("]")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        return try {
            val tempFiles = mutableMapOf<String, String>()
            session.parseBody(tempFiles)

            val tempPath = tempFiles["file"]
                ?: return textResponse(Response.Status.BAD_REQUEST, "No file received")

            val content = File(tempPath).readText()

            try {
                Config.parse(content.reader().buffered())
            } catch (e: Exception) {
                val reason = (e as? BadConfigException)?.let { "${it.reason} in ${it.section} ${it.location}" } ?: e.message
                return textResponse(Response.Status.BAD_REQUEST, "Not a valid WireGuard config: $reason")
            }

            val rawName = session.parameters["name"]?.firstOrNull()?.trim()
            val filename = session.parameters["filename"]?.firstOrNull() ?: "profile"
            val name = (rawName?.ifEmpty { null } ?: filename.substringBeforeLast(".")).take(MAX_NAME_LENGTH)

            onConfigReceived(name, content)
                .fold(
                    onSuccess = {
                        Log.i(TAG, "Config imported: $name")
                        textResponse(Response.Status.OK, "\"$name\" imported successfully")
                    },
                    onFailure = {
                        Log.e(TAG, "Import failed: ${it.message}")
                        textResponse(Response.Status.INTERNAL_ERROR, "Import failed: ${it.message}")
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}")
            textResponse(Response.Status.INTERNAL_ERROR, "Server error: ${e.message}")
        }
    }

    private fun handleCredentials(session: IHTTPSession): Response {
        return try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)

            val providerId = session.parameters["provider"]?.firstOrNull()?.trim()
                ?: return textResponse(Response.Status.BAD_REQUEST, "Missing provider")
            val username = session.parameters["username"]?.firstOrNull()
                ?.replace("\\s+".toRegex(), "")
                ?.ifEmpty { null }
                ?: return textResponse(Response.Status.BAD_REQUEST, "Missing username / account number")
            val password = session.parameters["password"]?.firstOrNull()?.trim() ?: ""

            if (username.isEmpty())
                return textResponse(Response.Status.BAD_REQUEST, "Username / account number is required")

            onCredentialsReceived(providerId, username, password)
                .fold(
                    onSuccess = { display ->
                        Log.i(TAG, "Credentials saved: $display")
                        textResponse(Response.Status.OK, display)
                    },
                    onFailure = {
                        Log.e(TAG, "Credential save failed: ${it.message}")
                        textResponse(Response.Status.INTERNAL_ERROR, it.message ?: "Unknown error")
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Credentials error: ${e.message}")
            textResponse(Response.Status.INTERNAL_ERROR, "Server error: ${e.message}")
        }
    }

    private fun textResponse(status: Response.Status, msg: String) =
        newFixedLengthResponse(status, MIME_PLAINTEXT, msg)

    /** Serves at most [max] connections at once; extra connections are closed immediately. */
    private class BoundedRunner(private val max: Int) : AsyncRunner {
        private val running = mutableListOf<ClientHandler>()

        override fun exec(code: ClientHandler) {
            synchronized(running) {
                if (running.size >= max) {
                    code.close()
                    return
                }
                running.add(code)
            }
            Thread(code, "UniVPN import").apply { isDaemon = true }.start()
        }

        override fun closed(clientHandler: ClientHandler) {
            synchronized(running) { running.remove(clientHandler) }
        }

        override fun closeAll() {
            synchronized(running) { running.toList() }.forEach { it.close() }
        }
    }

    companion object {
        private const val TAG = "UniVPN_Server"
        const val PORT = 8080
        private const val PIN_HEADER = "x-univpn-pin"          // NanoHTTPD lower-cases header names
        private const val MAX_BODY_BYTES = 64 * 1024L          // configs are a few hundred bytes
        private const val MAX_CONNECTIONS = 4
        private const val MAX_NAME_LENGTH = 80

        private val PAGE_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>UniVPN</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:system-ui,sans-serif;background:#111318;color:#fff;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:24px;gap:16px}
  .card{background:#1a1d24;border-radius:12px;padding:32px;width:100%;max-width:460px}
  h1{font-size:1.3rem;margin-bottom:4px}
  .sub{color:#5b6270;font-size:.85rem;margin-bottom:28px}
  .divider{height:1px;background:#2a2f3b;margin:24px 0}
  label{display:block;font-size:.8rem;color:#aaa;margin-bottom:6px;margin-top:16px}
  input,select{width:100%;padding:10px 12px;background:#21252e;border:1px solid #2a2f3b;border-radius:6px;color:#fff;font-size:.95rem;outline:none;appearance:none}
  input:focus,select:focus{border-color:#22c9b0}
  .drop{border:2px dashed #2a2f3b;border-radius:8px;padding:36px 20px;text-align:center;cursor:pointer;transition:all .15s;margin-top:8px}
  .drop:hover,.drop.over{border-color:#22c9b0;color:#22c9b0}
  .drop svg{display:block;margin:0 auto 10px;opacity:.5}
  .fname{font-size:.8rem;color:#22c9b0;margin-top:8px;min-height:1em}
  button{width:100%;margin-top:24px;padding:12px;background:#22c9b0;color:#111318;font-size:1rem;font-weight:700;border:none;border-radius:6px;cursor:pointer;transition:background .15s}
  button:hover{background:#1ba898}
  button:disabled{background:#2a2f3b;color:#5b6270;cursor:default}
  .msg{margin-top:16px;padding:12px;border-radius:6px;font-size:.9rem;display:none}
  .ok{background:#0a2420;border:1px solid #22c9b0;color:#22c9b0}
  .err{background:#2b0d0d;border:1px solid #ef4444;color:#ef4444}
  select option{background:#1a1d24}
  #providerFields{display:none}
</style>
</head>
<body>

<div class="card">
  <h1>UniVPN</h1>
  <p class="sub">Enter the PIN shown on the TV. It changes each time the Profiles screen is opened.</p>
  <label for="pin" style="margin-top:0">PIN</label>
  <input type="text" id="pin" inputmode="numeric" maxlength="6" autocomplete="off" placeholder="6-digit PIN">
</div>

<div class="card">
  <h1>WireGuard config</h1>
  <p class="sub">Import WireGuard configuration</p>
  <form id="wgForm">
    <label for="n">Profile name <span style="color:#3d4250">(optional — uses filename if blank)</span></label>
    <input type="text" id="n" name="name" placeholder="e.g. UK London">
    <label>Config file</label>
    <div class="drop" id="drop">
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
      Drop .conf file here or click to browse
      <input type="file" id="file" name="file" accept=".conf" style="display:none">
    </div>
    <div class="fname" id="fname"></div>
    <button type="submit" id="wgBtn">Upload</button>
  </form>
  <div class="msg" id="wgMsg"></div>
</div>

<div class="card">
  <h1>Provider Account</h1>
  <p class="sub">Save credentials for a commercial VPN provider</p>
  <form id="credForm">
    <label for="provider">Provider</label>
    <select id="provider" name="provider">
      <option value="">— Select provider —</option>
    </select>
    <div id="providerFields"></div>
    <button type="submit" id="credBtn" disabled>Save Account</button>
  </form>
  <div class="msg" id="credMsg"></div>
</div>

<script>
// ── WireGuard upload ──────────────────────────────────────────────────────────
const drop=document.getElementById('drop'),fi=document.getElementById('file'),fname=document.getElementById('fname');
drop.addEventListener('click',()=>fi.click());
drop.addEventListener('dragover',e=>{e.preventDefault();drop.classList.add('over')});
drop.addEventListener('dragleave',()=>drop.classList.remove('over'));
drop.addEventListener('drop',e=>{e.preventDefault();drop.classList.remove('over');fi.files=e.dataTransfer.files;fname.textContent=fi.files[0]?.name||''});
fi.addEventListener('change',()=>fname.textContent=fi.files[0]?.name||'');
document.getElementById('wgForm').addEventListener('submit',async e=>{
  e.preventDefault();
  const btn=document.getElementById('wgBtn'),msg=document.getElementById('wgMsg');
  if(!fi.files[0]){show(msg,'Select a .conf file first',false);return}
  btn.disabled=true;btn.textContent='Uploading…';
  const fd=new FormData();
  fd.append('file',fi.files[0],fi.files[0].name);
  fd.append('name',document.getElementById('n').value);
  fd.append('filename',fi.files[0].name);
  try{
    const r=await fetch('/upload',{method:'POST',body:fd,headers:pinHeader()});
    const t=await r.text();
    show(msg,t,r.ok);
    if(r.ok){document.getElementById('wgForm').reset();fname.textContent=''}
  }catch(err){show(msg,'Network error: '+err.message,false)}
  btn.disabled=false;btn.textContent='Upload';
});

// ── Provider credentials ──────────────────────────────────────────────────────
let providers=[];
async function loadProviders(){
  try{
    const r=await fetch('/providers');
    providers=await r.json();
    const sel=document.getElementById('provider');
    providers.forEach(p=>{
      const o=document.createElement('option');
      o.value=p.id;o.textContent=p.name;
      sel.appendChild(o);
    });
  }catch(e){console.warn('Could not load providers',e)}
}
loadProviders();

document.getElementById('provider').addEventListener('change',function(){
  const p=providers.find(x=>x.id===this.value);
  const wrap=document.getElementById('providerFields');
  const btn=document.getElementById('credBtn');
  if(!p){wrap.style.display='none';wrap.innerHTML='';btn.disabled=true;return}
  wrap.innerHTML='';
  p.fields.forEach(f=>{
    const lbl=document.createElement('label');lbl.textContent=f.label;
    const inp=document.createElement('input');
    inp.type=f.type;inp.name=f.key;inp.placeholder=f.hint||'';inp.required=true;inp.autocomplete='off';
    wrap.appendChild(lbl);wrap.appendChild(inp);
  });
  wrap.style.display='block';
  btn.disabled=false;
});

document.getElementById('credForm').addEventListener('submit',async e=>{
  e.preventDefault();
  const btn=document.getElementById('credBtn'),msg=document.getElementById('credMsg');
  btn.disabled=true;btn.textContent='Verifying…';
  const fd=new FormData(document.getElementById('credForm'));
  // ensure password field exists even for providers that don't use it
  if(!fd.has('password')) fd.append('password','');
  try{
    const r=await fetch('/credentials',{method:'POST',body:fd,headers:pinHeader()});
    const t=await r.text();
    show(msg,t,r.ok);
    if(r.ok) document.getElementById('credForm').reset();
  }catch(err){show(msg,'Network error: '+err.message,false)}
  btn.disabled=false;btn.textContent='Save Account';
});

function pinHeader(){return {'X-UniVPN-PIN':document.getElementById('pin').value.trim()}}

function show(el,t,ok){el.textContent=t;el.className='msg '+(ok?'ok':'err');el.style.display='block'}
</script>
</body>
</html>
        """.trimIndent()
    }
}
