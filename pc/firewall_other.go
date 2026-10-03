//go:build !windows

package main

// Windows Firewall does not exist here - the macOS/Linux firewall either
// allows loopback-created listeners or prompts on its own.
func ensureFirewall() {}
