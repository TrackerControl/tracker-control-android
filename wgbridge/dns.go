package wgbridge

import (
	"encoding/binary"
	"net"
	"strings"
)

const (
	dnsTypeA    = 1
	dnsTypeAAAA = 28
	dnsClassIN  = 1

	ipProtoFragment = 44
	ipProtoHopByHop = 0
	ipProtoRouting  = 43
	ipProtoUDP      = 17
	ipProtoDstOpts  = 60
)

func inspectDNSResponse(packet []byte, recorder DnsRecorder) {
	if recorder == nil || len(packet) < 1 {
		return
	}

	payload, ok := udpPayloadFromDNSResponse(packet)
	if !ok {
		return
	}
	for _, rr := range parseDNSAnswers(payload) {
		recordDNSAnswer(recorder, rr)
	}
}

func udpPayloadFromDNSResponse(packet []byte) ([]byte, bool) {
	version := packet[0] >> 4
	switch version {
	case 4:
		if len(packet) < 20 {
			return nil, false
		}
		ihl := int(packet[0]&0x0f) * 4
		if ihl < 20 || len(packet) < ihl+8 || packet[9] != ipProtoUDP {
			return nil, false
		}
		total := int(binary.BigEndian.Uint16(packet[2:4]))
		if total <= 0 || total > len(packet) {
			total = len(packet)
		}
		return udpDNSPayload(packet[ihl:total])
	case 6:
		if len(packet) < 40 {
			return nil, false
		}
		payloadLen := int(binary.BigEndian.Uint16(packet[4:6]))
		total := 40 + payloadLen
		if payloadLen == 0 || total > len(packet) {
			total = len(packet)
		}
		next := int(packet[6])
		off := 40
		for {
			if next == ipProtoUDP {
				if total < off+8 {
					return nil, false
				}
				return udpDNSPayload(packet[off:total])
			}
			if next == ipProtoFragment {
				return nil, false
			}
			if !isIPv6ExtHeader(next) || total < off+2 {
				return nil, false
			}
			hdrLen := (int(packet[off+1]) + 1) * 8
			if hdrLen < 8 || total < off+hdrLen {
				return nil, false
			}
			next = int(packet[off])
			off += hdrLen
		}
	default:
		return nil, false
	}
}

func udpDNSPayload(udp []byte) ([]byte, bool) {
	if len(udp) < 8 || binary.BigEndian.Uint16(udp[0:2]) != 53 {
		return nil, false
	}
	udpLen := int(binary.BigEndian.Uint16(udp[4:6]))
	if udpLen < 8 {
		return nil, false
	}
	if udpLen > len(udp) {
		if udpLen == 0 {
			udpLen = len(udp)
		} else {
			return nil, false
		}
	}
	return udp[8:udpLen], true
}

func isIPv6ExtHeader(next int) bool {
	return next == ipProtoHopByHop ||
		next == ipProtoRouting ||
		next == ipProtoDstOpts
}

func recordDNSAnswer(recorder DnsRecorder, rr dnsAnswer) {
	defer func() {
		_ = recover()
	}()
	recorder.RecordDns(rr.qname, rr.aname, rr.resource, rr.ttl)
}

type dnsAnswer struct {
	qname    string
	aname    string
	resource string
	ttl      int32
}

func parseDNSAnswers(msg []byte) []dnsAnswer {
	if len(msg) < 12 {
		return nil
	}
	flags := binary.BigEndian.Uint16(msg[2:4])
	if flags&0x8000 == 0 || flags&0x7800 != 0 {
		return nil
	}

	qdcount := int(binary.BigEndian.Uint16(msg[4:6]))
	ancount := int(binary.BigEndian.Uint16(msg[6:8]))
	if qdcount <= 0 || ancount <= 0 {
		return nil
	}

	off := 12
	qname := ""
	for q := 0; q < qdcount; q++ {
		name, next, ok := readDNSName(msg, off, 0)
		if !ok || next+4 > len(msg) {
			return nil
		}
		if q == 0 {
			qname = name
		}
		off = next + 4
	}
	if qname == "" {
		return nil
	}

	var answers []dnsAnswer
	for a := 0; a < ancount; a++ {
		aname, next, ok := readDNSName(msg, off, 0)
		if !ok || next+10 > len(msg) {
			return answers
		}
		typ := binary.BigEndian.Uint16(msg[next : next+2])
		class := binary.BigEndian.Uint16(msg[next+2 : next+4])
		ttl64 := int64(binary.BigEndian.Uint32(msg[next+4 : next+8]))
		rdlen := int(binary.BigEndian.Uint16(msg[next+8 : next+10]))
		rdata := next + 10
		if rdlen < 0 || rdata+rdlen > len(msg) {
			return answers
		}

		if class == dnsClassIN {
			switch typ {
			case dnsTypeA:
				if rdlen == net.IPv4len {
					answers = append(answers, dnsAnswer{
						qname:    qname,
						aname:    aname,
						resource: net.IP(msg[rdata : rdata+rdlen]).String(),
						ttl:      clampTTL(ttl64),
					})
				}
			case dnsTypeAAAA:
				if rdlen == net.IPv6len {
					answers = append(answers, dnsAnswer{
						qname:    qname,
						aname:    aname,
						resource: net.IP(msg[rdata : rdata+rdlen]).String(),
						ttl:      clampTTL(ttl64),
					})
				}
			}
		}
		off = rdata + rdlen
	}
	return answers
}

func readDNSName(msg []byte, off int, depth int) (string, int, bool) {
	if depth > 8 || off < 0 || off >= len(msg) {
		return "", 0, false
	}
	var labels []string
	next := off
	for {
		if off >= len(msg) {
			return "", 0, false
		}
		l := int(msg[off])
		switch l & 0xc0 {
		case 0xc0:
			if off+1 >= len(msg) {
				return "", 0, false
			}
			ptr := ((l & 0x3f) << 8) | int(msg[off+1])
			name, _, ok := readDNSName(msg, ptr, depth+1)
			if !ok {
				return "", 0, false
			}
			if name != "" {
				labels = append(labels, strings.Split(name, ".")...)
			}
			return strings.Join(labels, "."), off + 2, true
		case 0x00:
			if l == 0 {
				return strings.Join(labels, "."), off + 1, true
			}
			off++
			if l > 63 || off+l > len(msg) {
				return "", 0, false
			}
			labels = append(labels, string(msg[off:off+l]))
			off += l
			next = off
		default:
			return "", next, false
		}
	}
}

func clampTTL(ttl int64) int32 {
	if ttl < 0 {
		return 0
	}
	if ttl > 1<<31-1 {
		return 1<<31 - 1
	}
	return int32(ttl)
}
