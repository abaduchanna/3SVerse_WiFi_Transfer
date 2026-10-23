//go:build windows

package main

// firewall_windows.go - one-time Windows Firewall rule so the phone can reach
// this exe. "PC unreachable" on the phone is almost always the Windows
// inbound firewall dropping TCP 8765: the discovery broadcast (PC -> phone,
// outbound) arrives fine, but the phone's TCP connect never does.
//
// Flow: skip if rules already verified (marker file) -> try adding directly
// (works when elevated) -> otherwise re-run netsh elevated once (UAC prompt)
// -> verify -> marker. The user can skip all of it with -no-firewall.

import (
        "fmt"
        "os"
        "os/exec"
        "path/filepath"
        "strings"
        "time"
)

const fwRuleName = "3SVerse WiFi Transfer"

func firewallMarkerPath() string {
        local := os.Getenv("LOCALAPPDATA")
        if local == "" {
                return ""
        }
        return filepath.Join(local, "3SVerseWiFiTransfer", "firewall.done")
}

func firewallRulesExist() bool {
        out, err := exec.Command("netsh", "advfirewall", "firewall", "show",
                "rule", "name="+fwRuleName).CombinedOutput()
        if err != nil {
                // netsh exits non-zero when no rule matches
                return false
        }
        return strings.Contains(string(out), fwRuleName)
}

func firewallAddDirect() bool {
        for _, args := range [][]string{
                {"advfirewall", "firewall", "add", "rule", "name=" + fwRuleName,
                        "dir=in", "action=allow", "protocol=TCP", "localport=8765", "profile=any"},
                {"advfirewall", "firewall", "add", "rule", "name=" + fwRuleName,
                        "dir=in", "action=allow", "protocol=UDP", "localport=8766", "profile=any"},
        } {
                cmd := exec.Command("netsh", args...)
                if out, err := cmd.CombinedOutput(); err != nil {
                        fmt.Println("  firewall: direct add failed:", strings.TrimSpace(string(out)))
                        return false
                }
        }
        return true
}

// firewallAddElevated re-runs the two netsh adds inside an elevated cmd
// (Windows shows one UAC prompt). Waits up to 90s for the user to decide.
func firewallAddElevated() bool {
        ps := fmt.Sprintf(
                `Start-Process cmd -ArgumentList '/c netsh advfirewall firewall add rule name="%s" dir=in action=allow protocol=TCP localport=8765 profile=any && netsh advfirewall firewall add rule name="%s" dir=in action=allow protocol=UDP localport=8766 profile=any' -Verb RunAs -Wait`,
                fwRuleName, fwRuleName)
        cmd := exec.Command("powershell", "-NoProfile", "-Command", ps)
        cmd.Stdout, cmd.Stderr = nil, nil
        if err := cmd.Start(); err != nil {
                return false
        }
        done := make(chan error, 1)
        go func() { done <- cmd.Wait() }()
        select {
        case <-done:
        case <-time.After(90 * time.Second):
                return false
        }
        return firewallRulesExist()
}

func ensureFirewall() {
        fmt.Println("  Firewall    :  setting up rules (TCP 8765, UDP 8766)...")
        if fwMarker := firewallMarkerPath(); fwMarker != "" {
                if _, err := os.Stat(fwMarker); err == nil {
                        return // already set up on a previous run
                }
        }
        fmt.Println("  Firewall    :  ensuring inbound rules (TCP 8765, UDP 8766)...")
        if firewallRulesExist() {
                fmt.Println("  Firewall    :  rules already present.")
                markFirewallDone()
                return
        }
        if firewallAddDirect() {
                fmt.Println("  Firewall    :  rules added.")
                markFirewallDone()
                return
        }
        fmt.Println("  Firewall    :  admin rights needed - Windows may show a")
        fmt.Println("                 permission prompt. Click 'Yes' once.")
        if firewallAddElevated() {
                fmt.Println("  Firewall    :  rules added (elevated).")
                markFirewallDone()
                return
        }
        fmt.Println("  Firewall    :  could not add rules automatically. If the")
        fmt.Println("                 phone says 'PC unreachable', run this exe as")
        fmt.Println("                 Administrator once, or add inbound rules for")
        fmt.Println("                 TCP 8765 / UDP 8766 in Windows Firewall.")
}

func markFirewallDone() {
        marker := firewallMarkerPath()
        if marker == "" {
                return
        }
        _ = os.MkdirAll(filepath.Dir(marker), 0o755)
        _ = os.WriteFile(marker, []byte(version), 0o644)
}
