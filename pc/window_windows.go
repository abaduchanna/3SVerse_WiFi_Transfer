//go:build windows

package main

// window_windows.go - native app window for the dashboard.
//
// The dashboard HTML is served on loopback by the same exe; this opens it in
// a real Win32 window (WebView2) so it feels like a desktop app instead of a
// browser tab. Pure Go (no cgo): the WebView2Loader.dll is embedded and loaded
// from memory by jchv/go-winloader. If the WebView2 runtime or window creation
// is unavailable, openAppWindow returns false and main() falls back to the
// Edge/Chrome app-mode window, then to the default browser.

import (
        "os"
        "path/filepath"
        "runtime"
        "unsafe"

        "github.com/jchv/go-webview2"
        "golang.org/x/sys/windows"
        "golang.org/x/sys/windows/registry"
)

// webView2RuntimeAvailable does a cheap registry pre-check so we never hit the
// library's log.Fatal path when the Evergreen runtime is missing.
func webView2RuntimeAvailable() bool {
        keys := []string{
                `SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}`,
                `SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}`,
        }
        for _, k := range keys {
                v, err := registry.OpenKey(registry.LOCAL_MACHINE, k, registry.QUERY_VALUE)
                if err != nil {
                        continue
                }
                pv, _, err := v.GetStringValue("pv")
                v.Close()
                if err == nil && pv != "" && pv != "0.0.0.0" {
                        return true
                }
        }
        return false
}

// webView2DataDir returns a stable, writable user-data folder for the
// WebView2 runtime. The library's default is AppData\Roaming\<exe-name>,
// which breaks when the exe is run from a different/elevated account (the
// runtime then shows "Microsoft Edge can't read and write to its data
// directory" and the window stays white).
//
// We go one step further than just creating the folder: the runtime's own
// EBWebView subfolder is pre-created and a real write test is performed
// inside it. If ANY step fails (locked-down profile, AV policy, permissions),
// we return false and the caller falls back to a browser - no dialog, no
// blank window.
func webView2DataDir() (string, bool) {
        base := os.Getenv("LOCALAPPDATA")
        if base == "" {
                base = os.TempDir()
        }
        dir := filepath.Join(base, "3SVerseWiFiTransfer", "WebView2")
        eb := filepath.Join(dir, "EBWebView")
        if err := os.MkdirAll(eb, 0o755); err != nil {
                return "", false
        }
        // real write test inside the folder the runtime will actually use
        probe := filepath.Join(eb, ".write_test")
        if err := os.WriteFile(probe, []byte("ok"), 0o644); err != nil {
                return "", false
        }
        _ = os.Remove(probe)
        // official runtime also honors this env var as the default data folder
        _ = os.Setenv("WEBVIEW2_USER_DATA_FOLDER", dir)
        return dir, true
}

// openAppWindow blocks until the user closes the window. Returns true when a
// window was actually created (main shuts down after it closes), false when
// the caller should fall back to a browser.
func openAppWindow(url string) bool {
        if !webView2RuntimeAvailable() {
                return false
        }
        dataDir, ok := webView2DataDir()
        if !ok {
                return false
        }
        // Win32 message loops must stay on one OS thread.
        runtime.LockOSThread()
        defer runtime.UnlockOSThread()

        w := webview2.NewWithOptions(webview2.WebViewOptions{
                Debug:     false,
                DataPath:  dataDir,
                WindowOptions: webview2.WindowOptions{
                        Title:  "3SVerse WiFi Transfer",
                        Width:  1160,
                        Height: 800,
                        Center: true,
                        IconId: 1, // RT_GROUP_ICON id written by goversioninfo
                },
        })
        if w == nil {
                closeOrphanWebView() // kill the blank white host window
                return false
        }
        w.Navigate(url)
        w.Run()
        return true
}

// closeOrphanWebView closes the WebView2 host window the library leaves
// behind when environment/embed creation fails (it creates + SHOWS the
// Win32 window first, then returns false on Embed failure without
// destroying it - which used to leave a blank white window on screen).
func closeOrphanWebView() {
        user32 := windows.NewLazySystemDLL("user32.dll")
        find := user32.NewProc("FindWindowW")
        post := user32.NewProc("PostMessageW")
        cls, _ := windows.UTF16PtrFromString("webview") // jchv's host window class
        hwnd, _, _ := find.Call(uintptr(unsafe.Pointer(cls)), 0)
        if hwnd != 0 {
                post.Call(hwnd, 0x0010 /*WM_CLOSE*/, 0, 0)
        }
}
