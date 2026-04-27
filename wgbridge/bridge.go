// Package wgbridge embeds wireguard-go inside another VpnService process.
// It hands wireguard-go a custom tun.Device whose Read pulls outbound IP
// packets from a Unix socketpair fd that the C side of TrackerControl
// (jni/netguard/ip.c) writes into when the WG hijack is active. Inbound
// (decrypted) packets are written directly to the VpnService TUN fd, so
// the C code never sees them.
//
// The outer UDP socket that wireguard-go uses to talk to the peer is
// VpnService.protect()-ed via a Java callback, otherwise its packets
// would be re-captured by NetGuard's TUN and loop forever.
//
// Build:
//   gomobile bind -target=android -androidapi 23 -o ../app/libs/wgbridge.aar .
package wgbridge

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"sync"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// Protector is implemented by the Java side. Returns true if VpnService.protect(fd)
// succeeded.
type Protector interface {
	Protect(fd int32) bool
}

// Logger is implemented by the Java side to receive wireguard-go log lines.
type Logger interface {
	Verbosef(format string)
	Errorf(format string)
}

// Tunnel is the gomobile-bound handle. Stop() must be called from Java when
// the VpnService is torn down.
type Tunnel struct {
	dev    *device.Device
	tunDev *socketpairTun
	stop   sync.Once
}

// StartTunnel boots wireguard-go.
//
//	uapiConfig    UAPI text produced by WgConfig.toUapi() on the Java side.
//	outboundRxFd  read end of a socketpair held by C; C writes raw IP packets,
//	              we read them and feed wireguard-go.
//	tunWriteFd    VpnService TUN fd; we write decrypted inbound IP packets here.
//	mtu           payload MTU (typically 1420).
//	protect       Java callback to VpnService.protect(int).
//	logger        Java callback for wireguard-go log lines (may be nil).
//
// The supplied fds are duplicated; Stop() closes only our duplicates.
func StartTunnel(uapiConfig string, outboundRxFd int32, tunWriteFd int32, mtu int32,
	protect Protector, logger Logger) (*Tunnel, error) {

	if protect == nil {
		return nil, errors.New("protect must not be nil")
	}

	rxDup, err := unix.Dup(int(outboundRxFd))
	if err != nil {
		return nil, fmt.Errorf("dup outbound fd: %w", err)
	}
	if err := unix.SetNonblock(rxDup, true); err != nil {
		_ = unix.Close(rxDup)
		return nil, fmt.Errorf("set nonblock: %w", err)
	}
	txDup, err := unix.Dup(int(tunWriteFd))
	if err != nil {
		_ = unix.Close(rxDup)
		return nil, fmt.Errorf("dup tun fd: %w", err)
	}

	t := &socketpairTun{
		rxFile: os.NewFile(uintptr(rxDup), "wg-outbound-rx"),
		txFd:   txDup,
		mtu:    int(mtu),
		events: make(chan tun.Event, 4),
	}
	t.events <- tun.EventUp

	// Snapshot UDP fds before wireguard-go opens its outer sockets, so we
	// can identify exactly the new ones afterwards and protect those — and
	// only those. Avoids accidentally protecting unrelated sockets that
	// belong to anyone else in the process.
	udpBefore := snapshotUdpFds()

	dev := device.NewDevice(t, conn.NewStdNetBind(), newDeviceLogger(logger))

	if err := dev.IpcSet(uapiConfig); err != nil {
		dev.Close()
		_ = t.Close()
		return nil, fmt.Errorf("IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		_ = t.Close()
		return nil, fmt.Errorf("device up: %w", err)
	}

	// Protect each new UDP socket so its egress bypasses the VpnService TUN.
	udpAfter := snapshotUdpFds()
	for fd := range udpAfter {
		if _, existed := udpBefore[fd]; existed {
			continue
		}
		if !protect.Protect(int32(fd)) {
			dev.Close()
			_ = t.Close()
			return nil, fmt.Errorf("VpnService.protect(%d) failed", fd)
		}
	}

	return &Tunnel{dev: dev, tunDev: t}, nil
}

// Stop tears down wireguard-go and closes the duplicated fds.
func (t *Tunnel) Stop() {
	t.stop.Do(func() {
		t.dev.Close()
		_ = t.tunDev.Close()
	})
}

// socketpairTun implements tun.Device, sourcing outbound IP packets from a
// Unix socketpair fd and emitting decrypted inbound IP packets directly to
// the VpnService TUN fd.
type socketpairTun struct {
	rxFile *os.File // outbound: C side writes; we Read here
	txFd   int      // inbound:  we write decrypted IP packets to the VpnService TUN
	mtu    int
	events chan tun.Event
	mu     sync.Mutex
	closed bool
}

func (t *socketpairTun) File() *os.File           { return nil }
func (t *socketpairTun) MTU() (int, error)        { return t.mtu, nil }
func (t *socketpairTun) Name() (string, error)    { return "wgbridge", nil }
func (t *socketpairTun) Events() <-chan tun.Event { return t.events }
func (t *socketpairTun) BatchSize() int           { return 1 }

func (t *socketpairTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	n, err := t.rxFile.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

func (t *socketpairTun) Write(bufs [][]byte, offset int) (int, error) {
	for i, b := range bufs {
		if _, err := unix.Write(t.txFd, b[offset:]); err != nil {
			return i, err
		}
	}
	return len(bufs), nil
}

func (t *socketpairTun) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return nil
	}
	t.closed = true
	close(t.events)
	err1 := t.rxFile.Close()
	err2 := unix.Close(t.txFd)
	if err1 != nil {
		return err1
	}
	return err2
}

// snapshotUdpFds enumerates UDP sockets in the current process by reading
// /proc/self/fd and filtering by readlink target prefix "socket:". A
// secondary filter (SO_TYPE == SOCK_DGRAM) further narrows it to UDP. The
// returned set's keys are the integer fds.
func snapshotUdpFds() map[int]struct{} {
	out := make(map[int]struct{})
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return out
	}
	for _, e := range entries {
		fd, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		// Skip stdio.
		if fd < 3 {
			continue
		}
		// Filter by socket type.
		stype, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_TYPE)
		if err != nil || stype != unix.SOCK_DGRAM {
			continue
		}
		// Filter by socket family.
		family, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_DOMAIN)
		if err != nil || (family != unix.AF_INET && family != unix.AF_INET6) {
			continue
		}
		out[fd] = struct{}{}
	}
	return out
}

func newDeviceLogger(l Logger) *device.Logger {
	if l == nil {
		return &device.Logger{
			Verbosef: func(string, ...any) {},
			Errorf:   func(string, ...any) {},
		}
	}
	return &device.Logger{
		Verbosef: func(format string, args ...any) {
			l.Verbosef(fmt.Sprintf(format, args...))
		},
		Errorf: func(format string, args ...any) {
			l.Errorf(fmt.Sprintf(format, args...))
		},
	}
}
