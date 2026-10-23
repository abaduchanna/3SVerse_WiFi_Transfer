//go:build !windows

package main

import (
	"syscall"
)

type usageStatus struct {
	Free uint64
}

func freeSpace(path string) (usageStatus, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return usageStatus{}, err
	}
	return usageStatus{Free: st.Bavail * uint64(st.Bsize)}, nil
}
