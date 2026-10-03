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
	"runtime"

	"github.com/jchv/go-webview2"
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

// openAppWindow blocks until the user closes the window. Returns true when a
// window was actually created (main shuts down after it closes), false when
// the caller should fall back to a browser.
func openAppWindow(url string) bool {
	if !webView2RuntimeAvailable() {
		return false
	}
	// Win32 message loops must stay on one OS thread.
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	w := webview2.NewWithOptions(webview2.WebViewOptions{
		Debug: false,
		WindowOptions: webview2.WindowOptions{
			Title:  "3SVerse WiFi Transfer",
			Width:  1160,
			Height: 800,
			Center: true,
			IconId: 1, // RT_GROUP_ICON id written by goversioninfo
		},
	})
	if w == nil {
		return false
	}
	w.Navigate(url)
	w.Run()
	return true
}
