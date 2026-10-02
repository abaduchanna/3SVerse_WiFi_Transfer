// 3SVerse WiFi Transfer - PC receiver (single static exe, no installer).
//
// Run it, allow the Windows Firewall prompt (private networks), and open the
// 3SVerse WiFi Transfer app on your phone (same WiFi). The phone auto-finds
// this PC (UDP broadcast) and streams a picked folder here at WiFi speed.
//
// Endpoints used by the phone app:
//   GET  /hello            -> {"ok":true,"app":...,"free":<bytes>}
//   GET  /manifest         -> {"ok":true,"files":{"rel/path":size,...}}  (resume)
//   PUT  /file?path=<rel>  -> streams body to <save>/<rel> (atomic .part rename)
//   POST /done             -> summary
package main

import (
        "encoding/json"
        "fmt"
        "io"
        "net"
        "net/http"
        "os"
        "os/signal"
        "path/filepath"
        "regexp"
        "strings"
        "sync"
        "syscall"
        "time"
)

const (
        port      = 8765
        discPort  = 8766
        discMagic = "3SVERSE-XFER"
        version   = "1.2.1"
)

var driveRe = regexp.MustCompile(`^[A-Za-z]:`)

var reserved = map[string]bool{
        "CON": true, "PRN": true, "AUX": true, "NUL": true,
        "COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true,
        "COM6": true, "COM7": true, "COM8": true, "COM9": true,
        "LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true,
        "LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true,
}

// sanitizeRel validates + cleans a relative path from the phone.
func sanitizeRel(rel string) (string, error) {
        if rel == "" {
                return "", fmt.Errorf("empty path")
        }
        raw := strings.ReplaceAll(rel, "\\", "/")
        if strings.HasPrefix(raw, "/") || strings.HasPrefix(raw, "~") || driveRe.MatchString(raw) {
                return "", fmt.Errorf("absolute path forbidden")
        }
        raw = strings.Trim(raw, "/")
        if raw == "" {
                return "", fmt.Errorf("empty path")
        }
        var parts []string
        for _, seg := range strings.Split(raw, "/") {
                if seg == "" || seg == "." || seg == ".." {
                        return "", fmt.Errorf("bad path segment")
                }
                for _, ch := range []string{"<", ">", ":", "\"", "|", "?", "*"} {
                        seg = strings.ReplaceAll(seg, ch, "_")
                }
                stem := strings.ToUpper(strings.Split(seg, ".")[0])
                if reserved[stem] {
                        seg = "_" + seg
                }
                seg = strings.TrimRight(seg, " .")
                if seg == "" {
                        seg = "_"
                }
                parts = append(parts, seg)
        }
        return filepath.Join(parts...), nil
}

func lanIP() string {
        conn, err := net.Dial("udp", "8.8.8.8:80")
        if err != nil {
                return "127.0.0.1"
        }
        defer conn.Close()
        return conn.LocalAddr().(*net.UDPAddr).IP.String()
}

// ---------------------------------------------------------------- stats

type Stats struct {
        mu        sync.Mutex
        manifest  map[string]int64
        filesDone int64
        bytesDone int64
        speed     float64
        lastT     time.Time
        lastB     int64
}

func NewStats(root string) *Stats {
        s := &Stats{manifest: map[string]int64{}, lastT: time.Now()}
        filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
                if err != nil || info.IsDir() || strings.HasSuffix(path, ".part") {
                        return nil
                }
                rel, rerr := filepath.Rel(root, path)
                if rerr == nil {
                        s.manifest[filepath.ToSlash(rel)] = info.Size()
                }
                return nil
        })
        return s
}

func (s *Stats) tick() {
        s.mu.Lock()
        defer s.mu.Unlock()
        dt := time.Since(s.lastT).Seconds()
        if dt >= 1 {
                s.speed = float64(s.bytesDone-s.lastB) / dt
                s.lastT = time.Now()
                s.lastB = s.bytesDone
        }
}

func human(b int64) string {
        const KB = 1024
        const MB = KB * 1024
        const GB = MB * 1024
        const TB = GB * 1024
        switch {
        case b >= TB:
                return fmt.Sprintf("%.2f TB", float64(b)/TB)
        case b >= GB:
                return fmt.Sprintf("%.2f GB", float64(b)/GB)
        case b >= MB:
                return fmt.Sprintf("%.1f MB", float64(b)/MB)
        case b >= KB:
                return fmt.Sprintf("%.1f KB", float64(b)/KB)
        default:
                return fmt.Sprintf("%d B", b)
        }
}

// ---------------------------------------------------------------- server

type Server struct {
        root string
        st   *Stats
}

func (sv *Server) writeJSON(w http.ResponseWriter, obj interface{}) {
        sv.writeJSONCode(w, http.StatusOK, obj)
}

func (sv *Server) writeJSONCode(w http.ResponseWriter, code int, obj interface{}) {
        body, _ := json.Marshal(obj)
        w.Header().Set("Content-Type", "application/json")
        w.Header().Set("Content-Length", fmt.Sprint(len(body)))
        w.WriteHeader(code)
        w.Write(body)
}

func (sv *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
        switch {
        case r.URL.Path == "/" && r.Method == "GET":
                sv.serveGUI(w, r)

        case r.URL.Path == "/favicon.ico":
                serveBytes(w, "image/x-icon", faviconICO)

        case r.URL.Path == "/assets/logo.png":
                serveBytes(w, "image/png", logoPNG)

        case r.URL.Path == "/assets/spiral.webp":
                serveBytes(w, "image/webp", spiralWEBP)

        case r.URL.Path == "/assets/orb.webp":
                serveBytes(w, "image/webp", orbWEBP)

        case r.URL.Path == "/stats" && r.Method == "GET":
                sv.serveStats(w, r)

        case r.URL.Path == "/open-folder" && r.Method == "POST":
                sv.openFolder(w, r)

        case r.URL.Path == "/hello" && r.Method == "GET":
                var free uint64 = 0
                if us, err := diskUsage(sv.root); err == nil {
                        free = us.Free
                }
                sv.writeJSON(w, map[string]interface{}{
                        "ok": true, "app": "3SVerse WiFi Transfer", "version": version, "free": free,
                })

        case r.URL.Path == "/manifest" && r.Method == "GET":
                sv.st.mu.Lock()
                files := make(map[string]int64, len(sv.st.manifest))
                for k, v := range sv.st.manifest {
                        files[k] = v
                }
                sv.st.mu.Unlock()
                sv.writeJSON(w, map[string]interface{}{"ok": true, "files": files})

        case r.URL.Path == "/file" && r.Method == "PUT":
                sv.handleFile(w, r)

        case r.URL.Path == "/done" && r.Method == "POST":
                io.Copy(io.Discard, r.Body)
                r.Body.Close()
                sv.st.mu.Lock()
                f, b := sv.st.filesDone, sv.st.bytesDone
                sv.st.mu.Unlock()
                sv.writeJSON(w, map[string]interface{}{"ok": true, "files": f, "bytes": b})

        default:
                sv.writeJSON(w, map[string]interface{}{"ok": false, "error": "unknown route"})
        }
}

func (sv *Server) handleFile(w http.ResponseWriter, r *http.Request) {
        relQ := r.URL.Query().Get("path")
        rel, err := sanitizeRel(relQ)
        if err != nil {
                sv.writeJSONCode(w, http.StatusBadRequest, map[string]interface{}{"ok": false, "error": err.Error()})
                return
        }
        final := filepath.Join(sv.root, filepath.FromSlash(rel))
        tmp := final + ".part"

        n, err := streamToFile(r, tmp)
        if err == nil {
                err = os.Rename(tmp, final)
        }
        if err != nil {
                os.Remove(tmp)
                sv.writeJSONCode(w, http.StatusInternalServerError, map[string]interface{}{"ok": false, "error": err.Error()})
                return
        }
        sv.st.mu.Lock()
        sv.st.manifest[rel] = n
        sv.st.filesDone++
        sv.st.bytesDone += n
        f, b := sv.st.filesDone, sv.st.bytesDone
        sv.st.mu.Unlock()
        fmt.Printf("  [+] %s  (%s)   total: %d files / %s\n", rel, human(n), f, human(b))
        sv.writeJSON(w, map[string]interface{}{"ok": true, "size": n})
}

func streamToFile(r *http.Request, dst string) (int64, error) {
        if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
                return 0, err
        }
        f, err := os.Create(dst)
        if err != nil {
                return 0, err
        }
        defer f.Close()
        buf := make([]byte, 1<<20)
        return io.CopyBuffer(f, r.Body, buf)
}

// diskUsage returns free space via platform-specific helpers (disk_unix.go /
// disk_windows.go).
func diskUsage(path string) (usageStatus, error) {
        return freeSpace(path)
}

func broadcastLoop() {
        ip := lanIP()
        host, _ := os.Hostname()
        payload := []byte(fmt.Sprintf("%s|%s|%d|%s", discMagic, ip, port, host))
        addr := &net.UDPAddr{IP: net.IPv4bcast, Port: discPort}
        conn, err := net.DialUDP("udp", nil, addr)
        if err != nil {
                fmt.Println("broadcast error:", err)
                return
        }
        defer conn.Close()
        for {
                conn.Write(payload)
                time.Sleep(2 * time.Second)
        }
}

func main() {
        home, _ := os.UserHomeDir()
        saveDir := filepath.Join(home, "Downloads", "3SVerse WiFi Transfer")
        if len(os.Args) > 2 && os.Args[1] == "-dir" {
                saveDir = os.Args[2]
        }
        if err := os.MkdirAll(saveDir, 0o755); err != nil {
                fmt.Println("Cannot create save folder:", err)
                os.Exit(1)
        }

        sv := &Server{root: saveDir, st: NewStats(saveDir)}
        ip := lanIP()
        dash := fmt.Sprintf("http://127.0.0.1:%d/", port)
        fmt.Println("==================================================")
        fmt.Println("  3SVerse WiFi Transfer - PC Receiver  v" + version)
        fmt.Println("==================================================")
        fmt.Println("  Dashboard   :  " + dash + "  (opening in your browser)")
        fmt.Println("  Phone uses  :  " + ip + ":" + fmt.Sprint(port))
        fmt.Println("  Save folder :  " + saveDir)
        fmt.Println("  (to change save folder:  pc_server.exe -dir D:\\MyFolder)")
        fmt.Println("--------------------------------------------------")
        fmt.Println("  FIRST RUN: if Windows Firewall asks, tick")
        fmt.Println("  'Private networks' and click 'Allow access'.")
        fmt.Println("--------------------------------------------------")
        fmt.Println("  Waiting for the phone app on the same WiFi...")
        fmt.Println("")

        go broadcastLoop()
        // Studio-branded dashboard opens automatically (skip with -no-open).
        noOpen := false
        for _, a := range os.Args {
                if a == "-no-open" {
                        noOpen = true
                }
        }
        if !noOpen {
                go func() {
                        time.Sleep(800 * time.Millisecond)
                        openBrowser(dash)
                }()
        }
        go func() {
                t := time.NewTicker(1 * time.Second)
                for range t.C {
                        sv.st.tick()
                }
        }()

        srv := &http.Server{
                Addr:              fmt.Sprintf("0.0.0.0:%d", port),
                Handler:           sv,
                ReadHeaderTimeout: 15 * time.Second,
        }

        sig := make(chan os.Signal, 1)
        signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
        go func() {
                <-sig
                fmt.Println("\nShutting down...")
                srv.Close()
                os.Exit(0)
        }()

        if err := srv.ListenAndServe(); err != nil {
                fmt.Println("Server error:", err)
                os.Exit(1)
        }
}
