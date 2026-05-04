package wgbridge

import (
	"encoding/binary"
	"testing"
)

func TestParseDNSAnswersRecordsAAndAAAA(t *testing.T) {
	msg := dnsMessage(
		dnsQuestion("tracker.example", dnsTypeA),
		dnsAAnswer("tracker.example", 300, []byte{203, 0, 113, 7}),
		dnsAAAAAnswer("tracker.example", 60, []byte{
			0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 1,
		}),
	)

	answers := parseDNSAnswers(msg)
	if len(answers) != 2 {
		t.Fatalf("got %d answers, want 2", len(answers))
	}
	if answers[0].qname != "tracker.example" ||
		answers[0].aname != "tracker.example" ||
		answers[0].resource != "203.0.113.7" ||
		answers[0].ttl != 300 {
		t.Fatalf("unexpected A answer: %+v", answers[0])
	}
	if answers[1].resource != "2001:db8::1" || answers[1].ttl != 60 {
		t.Fatalf("unexpected AAAA answer: %+v", answers[1])
	}
}

func TestParseDNSAnswersIgnoresQueries(t *testing.T) {
	msg := dnsMessage(dnsQuestion("tracker.example", dnsTypeA))
	msg[2] = 0x01
	msg[3] = 0x00

	if answers := parseDNSAnswers(msg); len(answers) != 0 {
		t.Fatalf("got answers for query: %+v", answers)
	}
}

func TestUDPPayloadFromDNSResponseUsesUDPLength(t *testing.T) {
	msg := dnsMessage(
		dnsQuestion("tracker.example", dnsTypeA),
		dnsAAnswer("tracker.example", 300, []byte{203, 0, 113, 7}),
	)
	packet := ipv4UDP(msg)
	packet = append(packet, 0xaa, 0xbb, 0xcc)

	payload, ok := udpPayloadFromDNSResponse(packet)
	if !ok {
		t.Fatal("packet was not recognized")
	}
	if len(payload) != len(msg) {
		t.Fatalf("payload length = %d, want %d", len(payload), len(msg))
	}
}

func TestUDPPayloadFromDNSResponseWalksIPv6ExtensionHeader(t *testing.T) {
	msg := dnsMessage(
		dnsQuestion("tracker.example", dnsTypeA),
		dnsAAnswer("tracker.example", 300, []byte{203, 0, 113, 7}),
	)
	packet := ipv6UDPWithDestinationOptions(msg)

	payload, ok := udpPayloadFromDNSResponse(packet)
	if !ok {
		t.Fatal("packet was not recognized")
	}
	if len(payload) != len(msg) {
		t.Fatalf("payload length = %d, want %d", len(payload), len(msg))
	}
}

func TestInspectDNSResponseRecorderPanicDoesNotPropagate(t *testing.T) {
	msg := dnsMessage(
		dnsQuestion("tracker.example", dnsTypeA),
		dnsAAnswer("tracker.example", 300, []byte{203, 0, 113, 7}),
	)

	inspectDNSResponse(ipv4UDP(msg), panicRecorder{})
}

type panicRecorder struct{}

func (panicRecorder) RecordDns(string, string, string, int32) {
	panic("recording failed")
}

func dnsMessage(parts ...[]byte) []byte {
	msg := make([]byte, 12)
	binary.BigEndian.PutUint16(msg[0:2], 0x1234)
	binary.BigEndian.PutUint16(msg[2:4], 0x8180)
	binary.BigEndian.PutUint16(msg[4:6], 1)
	binary.BigEndian.PutUint16(msg[6:8], uint16(len(parts)-1))
	for _, part := range parts {
		msg = append(msg, part...)
	}
	return msg
}

func dnsQuestion(name string, typ uint16) []byte {
	out := dnsName(name)
	out = binary.BigEndian.AppendUint16(out, typ)
	out = binary.BigEndian.AppendUint16(out, dnsClassIN)
	return out
}

func dnsAAnswer(name string, ttl uint32, ip []byte) []byte {
	return dnsAnswerBytes(name, dnsTypeA, ttl, ip)
}

func dnsAAAAAnswer(name string, ttl uint32, ip []byte) []byte {
	return dnsAnswerBytes(name, dnsTypeAAAA, ttl, ip)
}

func dnsAnswerBytes(name string, typ uint16, ttl uint32, rdata []byte) []byte {
	out := dnsName(name)
	out = binary.BigEndian.AppendUint16(out, typ)
	out = binary.BigEndian.AppendUint16(out, dnsClassIN)
	out = binary.BigEndian.AppendUint32(out, ttl)
	out = binary.BigEndian.AppendUint16(out, uint16(len(rdata)))
	out = append(out, rdata...)
	return out
}

func dnsName(name string) []byte {
	var out []byte
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			out = append(out, byte(i-start))
			out = append(out, name[start:i]...)
			start = i + 1
		}
	}
	return append(out, 0)
}

func ipv4UDP(payload []byte) []byte {
	udpLen := 8 + len(payload)
	total := 20 + udpLen
	packet := make([]byte, total)
	packet[0] = 0x45
	binary.BigEndian.PutUint16(packet[2:4], uint16(total))
	packet[8] = 64
	packet[9] = ipProtoUDP
	copy(packet[12:16], []byte{10, 64, 0, 1})
	copy(packet[16:20], []byte{10, 0, 0, 2})
	binary.BigEndian.PutUint16(packet[20:22], 53)
	binary.BigEndian.PutUint16(packet[22:24], 12345)
	binary.BigEndian.PutUint16(packet[24:26], uint16(udpLen))
	copy(packet[28:], payload)
	return packet
}

func ipv6UDPWithDestinationOptions(payload []byte) []byte {
	udpLen := 8 + len(payload)
	totalPayload := 8 + udpLen
	packet := make([]byte, 40+totalPayload)
	packet[0] = 0x60
	binary.BigEndian.PutUint16(packet[4:6], uint16(totalPayload))
	packet[6] = ipProtoDstOpts
	packet[7] = 64
	packet[40] = ipProtoUDP
	packet[41] = 0
	udp := packet[48:]
	binary.BigEndian.PutUint16(udp[0:2], 53)
	binary.BigEndian.PutUint16(udp[2:4], 12345)
	binary.BigEndian.PutUint16(udp[4:6], uint16(udpLen))
	copy(udp[8:], payload)
	return packet
}
