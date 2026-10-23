//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

type usageStatus struct {
	Free uint64
}

// freeSpace uses GetDiskFreeSpaceExW via NewLazyDLL - pure stdlib, no cgo,
// no external modules (keeps the single-exe AV profile clean).
func freeSpace(path string) (usageStatus, error) {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	proc := kernel32.NewProc("GetDiskFreeSpaceExW")
	var free, total, avail uint64
	p16, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return usageStatus{}, err
	}
	r1, _, err2 := proc.Call(
		uintptr(unsafe.Pointer(p16)),
		uintptr(unsafe.Pointer(&avail)),
		uintptr(unsafe.Pointer(&total)),
		uintptr(unsafe.Pointer(&free)),
	)
	if r1 == 0 {
		return usageStatus{}, err2
	}
	return usageStatus{Free: free}, nil
}
