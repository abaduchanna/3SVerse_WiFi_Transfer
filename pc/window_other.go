//go:build !windows

package main

// openAppWindow is only implemented on Windows (WebView2). Other platforms
// fall back to opening the dashboard in the default browser.
func openAppWindow(url string) bool {
	return false
}
