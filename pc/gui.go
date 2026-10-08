package main

// gui.go - the Studio-branded browser dashboard for the PC receiver
// (user order: "wifi transfer app ka gui studio jesa kardo").
//
// The exe is a headless receiver; the GUI is served at http://<pc>:8765/
// and opens automatically on startup. Same brand system as the Studio app
// and 3sverse.com: near-black canvas, the hero ring spiral (120s/turn +
// 16px/12s float), the glossy orb (13s float + 140s/turn), cyan->magenta
// CTAs, stacked logo header, mono letter-spaced labels.
//
// Everything is embedded in the exe - the page works fully offline.

import (
        _ "embed"
        "encoding/json"
        "fmt"
        "net/http"
        "os"
        "os/exec"
        "path/filepath"
        "runtime"
        "time"
)

//go:embed assets/logo.png
var logoPNG []byte

//go:embed assets/spiral.webp
var spiralWEBP []byte

//go:embed assets/orb.webp
var orbWEBP []byte

//go:embed assets/favicon.ico
var faviconICO []byte

var startedAt = time.Now()

func (sv *Server) serveGUI(w http.ResponseWriter, _ *http.Request) {
        w.Header().Set("Content-Type", "text/html; charset=utf-8")
        _, _ = w.Write([]byte(guiHTML))
}

func serveBytes(w http.ResponseWriter, ctype string, b []byte) {
        w.Header().Set("Content-Type", ctype)
        w.Header().Set("Cache-Control", "max-age=3600")
        _, _ = w.Write(b)
}

// serveStats - live numbers for the dashboard (polled once a second).
func (sv *Server) serveStats(w http.ResponseWriter, _ *http.Request) {
        sv.st.mu.Lock()
        files, bytes, speed := sv.st.filesDone, sv.st.bytesDone, sv.st.speed
        sv.st.mu.Unlock()
        var free uint64 = 0
        if us, err := diskUsage(sv.root); err == nil {
                free = us.Free
        }
        oc, ob := sv.outboxStats()
        body, _ := json.Marshal(map[string]interface{}{
                "ok": true, "files": files, "bytes": bytes, "speed": speed,
                "free": free, "version": version, "dir": sv.root,
                "ip": lanIP(), "port": port, "uptime": int(time.Since(startedAt).Seconds()),
                "outbox": oc, "outboxBytes": ob,
        })
        w.Header().Set("Content-Type", "application/json")
        _, _ = w.Write(body)
}

// openFolder - POST: reveal the save folder in Explorer / Finder.
func (sv *Server) openFolder(w http.ResponseWriter, _ *http.Request) {
        var err error
        switch runtime.GOOS {
        case "windows":
                err = exec.Command("explorer", sv.root).Start()
        case "darwin":
                err = exec.Command("open", sv.root).Start()
        default:
                err = exec.Command("xdg-open", sv.root).Start()
        }
        if err != nil {
                sv.writeJSONCode(w, http.StatusInternalServerError,
                        map[string]interface{}{"ok": false, "error": err.Error()})
                return
        }
        sv.writeJSON(w, map[string]interface{}{"ok": true})
}

// openBrowser - open the dashboard like a desktop app: Edge/Chrome app-mode
// window first (chromeless, taskbar icon, no tabs), then the default browser.
func openBrowser(url string) {
        if runtime.GOOS == "windows" {
                candidates := []string{}
                for _, env := range []string{"ProgramFiles(x86)", "ProgramFiles", "LocalAppData"} {
                        root := os.Getenv(env)
                        if root == "" {
                                continue
                        }
                        candidates = append(candidates,
                                filepath.Join(root, `Microsoft\Edge\Application\msedge.exe`),
                                filepath.Join(root, `Google\Chrome\Application\chrome.exe`))
                }
                for _, exe := range candidates {
                        if _, err := os.Stat(exe); err != nil {
                                continue
                        }
                        if exec.Command(exe, "--app="+url, "--window-size=1180,820").Start() == nil {
                                return
                        }
                }
                _ = exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
                return
        }
        var err error
        switch runtime.GOOS {
        case "darwin":
                err = exec.Command("open", url).Start()
        default:
                err = exec.Command("xdg-open", url).Start()
        }
        if err != nil {
                fmt.Println("(open the dashboard manually at " + url + ")")
        }
}

const guiHTML = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#07060b">
<title>3SVerse WiFi Transfer — PC Receiver</title>
<link rel="icon" href="/favicon.ico">
<style>
:root{color-scheme:dark}
*{margin:0;padding:0;box-sizing:border-box}
body{font:14px/1.5 -apple-system,'Segoe UI',Roboto,Arial,sans-serif;background:#07060b;color:#f5f1e6;min-height:100vh;overflow-x:hidden}
.mono{font-family:'DM Mono',ui-monospace,'Cascadia Mono',Menlo,monospace}
#bgart{position:fixed;inset:0;overflow:hidden;pointer-events:none;z-index:0}
#bgart>div{position:absolute;will-change:transform}
#bgart img{position:relative;display:block;width:100%;height:auto;will-change:transform}
.bg-ring{right:-24vw;top:-14vh;width:min(52vw,640px);opacity:.9;animation:bgfloatR 12s ease-in-out infinite}
.bg-ring img{animation:bgspin 120s linear infinite}
.bg-orb{left:-10vw;bottom:-22vh;width:min(30vw,380px);opacity:.95;animation:bgfloatO 13s ease-in-out infinite}
.bg-orb img{animation:bgspin 140s linear infinite}
@keyframes bgspin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}
@keyframes bgfloatR{0%,100%{transform:translateY(-16px)}50%{transform:translateY(16px)}}
@keyframes bgfloatO{0%,100%{transform:translateY(-12px)}50%{transform:translateY(12px)}}
@media (prefers-reduced-motion:reduce){#bgart>div,#bgart img{animation:none}}
.wrap{position:relative;z-index:1;max-width:760px;margin:0 auto;padding:34px 20px 74px}
header{display:flex;flex-direction:column;align-items:center;text-align:center;gap:8px;margin-bottom:26px}
/* logo = the standardized 3SVerse wordmark (654x155 tight asset, same
   file the Android app uses - "new logo standardize kardo"). 60px tall
   keeps the user-approved header size (user: "xfer ka logo bada karne
   ka bola tha na?"). */
header img{height:60px;display:block}
/* header colors = the License Studio header (user: "header bhi same
   color ke kardo"): title word in brand cyan, eyebrow line cyan. */
header h1{font-size:15px;font-weight:700;letter-spacing:.2em;margin-top:4px}
header h1 .hl{color:#6ee7ef}
header .sub{font-size:9.5px;letter-spacing:.3em;color:#6ee7ef}
.chip{display:inline-flex;align-items:center;gap:8px;background:#0d0c14;border:1px solid #232130;border-radius:999px;padding:7px 16px;font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:#9b97b3;margin-bottom:22px}
.chip .dot{width:8px;height:8px;border-radius:50%;background:#c7ef70;box-shadow:0 0 10px #c7ef70}
.chip.busy .dot{background:#6ee7ef;box-shadow:0 0 10px #6ee7ef;animation:pulse 1s ease-in-out infinite}
@keyframes pulse{0%,100%{opacity:.4}50%{opacity:1}}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:14px}
@media (max-width:640px){.grid{grid-template-columns:repeat(2,1fr)}}
.card{background:#0d0c14;border:1px solid #232130;border-radius:14px;padding:16px}
.card .k{font-size:8.5px;letter-spacing:.16em;color:#9b97b3;text-transform:uppercase}
.card .v{font-size:22px;font-weight:700;margin-top:6px;color:#f5f1e6}
.card .v.cy{color:#6ee7ef}.card .v.lime{color:#c7ef70}.card .v.mag{color:#e44bd7}
.folder{margin-bottom:14px}
.folder .row{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.folder .path{font-size:12px;color:#f5f1e6;background:#12101a;border:1px solid #232130;border-radius:10px;padding:10px 12px;flex:1;min-width:220px;word-break:break-all}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;border:none;border-radius:11px;padding:11px 18px;font-size:13px;font-weight:700;cursor:pointer;letter-spacing:.04em;text-decoration:none}
.btn-primary{background:linear-gradient(90deg,#6ee7ef,#e44bd7);color:#07060b}
.btn-primary:hover{filter:brightness(1.1)}
.btn-ghost{background:transparent;border:1px solid #232130;color:#b9b5cc;font-weight:500}
.btn-ghost:hover{border-color:#6ee7ef;color:#6ee7ef}
.hint{font-size:11px;color:#9b97b3;margin-top:9px;line-height:1.6}
.logwrap{margin-top:4px}
.logwrap h2{font-size:9.5px;letter-spacing:.24em;color:#6ee7ef;font-weight:500;margin-bottom:9px}
#log{font-family:ui-monospace,Menlo,monospace;font-size:11px;color:#b9b5cc;background:#12101a;border:1px solid #232130;border-radius:12px;padding:12px;height:150px;overflow-y:auto;white-space:pre-wrap;word-break:break-all}
/* Fixed brand footer, the 3SVerse standard used by ALL four surfaces
   (xfer PC + xfer Android + Studio exe + Studio Android): 32px strip,
   bg #0d0c14, top border #232130, developed-by CENTER + version RIGHT,
   text #c7cbe0 (user: "xfer ka footer bhi standardize kardo"). */
footer{position:fixed;left:0;right:0;bottom:0;z-index:2;height:32px;line-height:31px;text-align:center;font-size:9px;font-weight:700;letter-spacing:.12em;color:#c7cbe0;background:#0d0c14;border-top:1px solid #232130;padding:0 12px}
footer .ver{position:absolute;right:10px;top:0;line-height:32px;letter-spacing:.08em;color:#8b87a0}
</style></head><body>
<div id="bgart" aria-hidden="true">
<div class="bg-ring"><img src="/assets/spiral.webp" alt=""></div>
<div class="bg-orb"><img src="/assets/orb.webp" alt=""></div>
</div>
<div class="wrap">
<header>
<img src="/assets/logo.png" alt="3S Verse">
<h1 class="mono">3SVERSE WIFI <span class="hl">TRANSFER</span></h1>
<div class="sub mono">PC RECEIVER &middot; PHONE &rarr; PC &middot; LOCAL WIFI &middot; NO CLOUD</div>
</header>
<div class="chip" id="chip"><span class="dot"></span><span id="chiptext">Waiting for the phone&hellip;</span></div>
<div class="grid">
<div class="card"><div class="k">Files received</div><div class="v cy" id="s-files">0</div></div>
<div class="card"><div class="k">Data received</div><div class="v" id="s-bytes">0 B</div></div>
<div class="card"><div class="k">Speed</div><div class="v mag" id="s-speed">0 B/s</div></div>
<div class="card"><div class="k">Free space</div><div class="v lime" id="s-free">&mdash;</div></div>
</div>
<div class="card folder">
<div class="k" style="font-size:8.5px;letter-spacing:.16em;color:#9b97b3;text-transform:uppercase;margin-bottom:8px">Save folder</div>
<div class="row">
<div class="path mono" id="s-dir">&hellip;</div>
<button class="btn btn-primary" onclick="openFolder()">Open folder</button>
</div>
<div class="hint">Files land here from the phone app. To use a different folder, restart the receiver with <span class="mono">-dir D:\MyFolder</span>. The dashboard URL is <span class="mono" id="s-url">&hellip;</span> (any browser on this WiFi can open it).</div>
</div>
<div class="card">
<div class="k" style="font-size:8.5px;letter-spacing:.16em;color:#9b97b3;text-transform:uppercase;margin-bottom:10px">Send to phone (PC &rarr; phone)</div>
<div class="row">
<button class="btn btn-primary" onclick="document.getElementById('pick-files').click()">Add files</button>
<button class="btn btn-ghost" onclick="document.getElementById('pick-folder').click()">Add folder</button>
<input type="file" id="pick-files" multiple style="display:none">
<input type="file" id="pick-folder" webkitdirectory style="display:none">
</div>
<div class="hint" id="send-status">Queued files wait here and are pulled into the phone's Downloads/3SVerse WiFi Transfer folder from the phone app (PC &rarr; Phone card). Sub-folders keep their structure.</div>
<div class="path mono" id="outbox-list" style="margin-top:9px;display:none;white-space:pre-wrap"></div>
<div class="row" style="margin-top:9px">
<button class="btn btn-ghost" onclick="clearOutbox()">Clear queue</button>
<span class="hint" id="outbox-summary" style="align-self:center"></span>
</div>
</div>
<div class="card logwrap">
<h2>RECEIVE LOG</h2>
<div id="log">Waiting for the phone app on the same WiFi&hellip;</div>
</div>
<footer class="mono">DEVELOPED BY WWW.3SVERSE.COM<span class="ver" id="s-ver">v?</span></footer>
</div>
<script>
var lastFiles=-1,lastBytes=-1,idle=true;
function human(b){if(b<1024)return b+' B';var v=b,u=['KB','MB','GB','TB'];for(var i=0;i<u.length;i++){v/=1024;if(v<1024)return v.toFixed(1)+' '+u[i]}return (v/1024).toFixed(1)+' PB'}
function poll(){fetch('/stats').then(function(r){return r.json()}).then(function(s){
document.getElementById('s-files').textContent=s.files;
document.getElementById('s-bytes').textContent=human(s.bytes);
document.getElementById('s-speed').textContent=human(Math.max(0,Math.round(s.speed)))+'/s';
document.getElementById('s-free').textContent=human(s.free);
document.getElementById('s-dir').textContent=s.dir;
document.getElementById('s-url').textContent='http://'+s.ip+':'+s.port+'/';
document.getElementById('s-ver').textContent='v'+s.version;
var busy=s.speed>0;
var chip=document.getElementById('chip');
var txt=document.getElementById('chiptext');
if(busy){chip.classList.add('busy');txt.textContent='Receiving\u2026';}
else if(s.files>0){chip.classList.remove('busy');txt.textContent='Idle \u00b7 last transfer: '+s.files+' files';}
else{chip.classList.remove('busy');txt.textContent='Waiting for the phone\u2026';}
var log=document.getElementById('log');
if(lastFiles>=0&&(s.files!=lastFiles||s.bytes>lastBytes)){
log.textContent='['+new Date().toLocaleTimeString()+'] received '+s.files+' files / '+human(s.bytes)+' total\n'+log.textContent;idle=false}
else if(!idle&&lastFiles>=0&&s.files==lastFiles&&s.bytes==lastBytes){/*quiet*/}
lastFiles=s.files;lastBytes=s.bytes;
}).catch(function(){})}
function openFolder(){fetch('/open-folder',{method:'POST'})}
function clearOutbox(){fetch('/outbox/clear',{method:'POST'}).then(function(){pollOutbox(true)})}
var lastOutboxCount=-1,sending=false;
function relName(file){return (file.webkitRelativePath&&file.webkitRelativePath!=='')?file.webkitRelativePath:file.name}
function queueFile(file,done){
fetch('/outbox?name='+encodeURIComponent(relName(file)),{method:'POST',body:file})
.then(function(r){return r.json()}).then(function(j){if(!j.ok)throw new Error(j.error||'queue failed');done(null,j)}).catch(function(e){done(e)})}
function queueAll(input){
var fs=Array.prototype.slice.call(input.files||[]);input.value='';
if(!fs.length)return;sending=true;
var i=0,failed=0;
function step(){
if(i>=fs.length){sending=false;
document.getElementById('send-status').textContent='Queued '+(fs.length-failed)+' of '+fs.length+' file(s) for the phone.';
pollOutbox(true);return}
document.getElementById('send-status').textContent='Queueing '+(i+1)+' / '+fs.length+': '+relName(fs[i]);
queueFile(fs[i],function(err){if(err)failed++;i++;step()})}
step()}
document.getElementById('pick-files').addEventListener('change',function(){queueAll(this)});
document.getElementById('pick-folder').addEventListener('change',function(){queueAll(this)});
function pollOutbox(force){
fetch('/outbox/list').then(function(r){return r.json()}).then(function(j){
if(!j.ok)return;var fs=j.files||[];
if(!force&&fs.length===lastOutboxCount)return;lastOutboxCount=fs.length;
var box=document.getElementById('outbox-list');
document.getElementById('outbox-summary').textContent=fs.length? (fs.length+' file(s) waiting for the phone'):'Queue is empty - the phone app pulls these automatically.';
if(!fs.length){box.style.display='none';box.textContent='';return}
box.style.display='block';
var lines=[];for(var i=0;i<fs.length&&i<12;i++){lines.push(fs[i].name+'  ('+human(fs[i].size)+')')}
if(fs.length>12)lines.push('... and '+(fs.length-12)+' more');
box.textContent=lines.join('\n')})}
setInterval(pollOutbox,2000);pollOutbox(true);
setInterval(poll,1000);poll();
</script>
</body></html>`
