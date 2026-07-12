package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;

public class DnsOverHttpsClientTest {
    @Test
    public void normalizeTransactionIdUsesZeroWithoutMutatingQuery() {
        byte[] query = new byte[]{0x12, 0x34, 0x01, 0x00};

        byte[] normalized = DnsOverHttpsClient.normalizeTransactionId(query);

        assertNotSame(query, normalized);
        assertArrayEquals(new byte[]{0x00, 0x00, 0x01, 0x00}, normalized);
        assertArrayEquals(new byte[]{0x12, 0x34, 0x01, 0x00}, query);
    }

    @Test
    public void transactionIdDoesNotChangeHttpCacheKey() {
        byte[] first = new byte[]{0x12, 0x34, 0x01, 0x00};
        byte[] second = new byte[]{0x56, 0x78, 0x01, 0x00};

        assertEquals(
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", first),
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", second));
    }

    @Test
    public void getQueryOmitsBase64Padding() {
        byte[] query = new byte[]{0x12, 0x34, 0x01, 0x00};

        String encoded = DnsOverHttpsClient
                .buildRequestUrl("https://dns.example/dns-query", query)
                .queryParameter("dns");

        assertEquals("AAABAA", encoded);
    }

    @Test
    public void dnsMessageContentsRemainPartOfHttpCacheKey() {
        byte[] recursive = new byte[]{0x12, 0x34, 0x01, 0x00};
        byte[] nonRecursive = new byte[]{0x12, 0x34, 0x00, 0x00};

        assertNotEquals(
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", recursive),
                DnsOverHttpsClient.buildRequestUrl("https://dns.example/dns-query", nonRecursive));
    }

    @Test
    public void cachedResponseRestoresIdAndSubtractsHttpAge() throws Exception {
        Message response = new Message(0);
        response.addRecord(new ARecord(Name.fromString("example.com."),
                DClass.IN, 300, InetAddress.getByName("192.0.2.1")), Section.ANSWER);
        byte[] query = new byte[]{0x12, 0x34};

        Message aged = new Message(DnsOverHttpsClient
                .ageDnsResponse(response.toWire(), 100, query));

        assertEquals(0x1234, aged.getHeader().getID());
        Record answer = aged.getSection(Section.ANSWER).get(0);
        assertEquals(200, answer.getTTL());
    }

    @Test
    public void cachedResponseNeverUnderflowsTtl() throws Exception {
        Message response = new Message(0);
        response.addRecord(new ARecord(Name.fromString("example.com."),
                DClass.IN, 5, InetAddress.getByName("192.0.2.1")), Section.ANSWER);

        Message aged = new Message(DnsOverHttpsClient
                .ageDnsResponse(response.toWire(), 100, new byte[]{0x00, 0x01}));

        assertEquals(0, aged.getSection(Section.ANSWER).get(0).getTTL());
    }

    @Test
    public void restoreTransactionIdPatchesIdWithoutTouchingRest() {
        byte[] response = new byte[]{0x00, 0x00, 0x01, 0x02, 0x03};
        byte[] query = new byte[]{0x12, 0x34, (byte) 0xFF};

        DnsOverHttpsClient.restoreTransactionId(response, query);

        assertArrayEquals(new byte[]{0x12, 0x34, 0x01, 0x02, 0x03}, response);
    }

    @Test
    public void freshResponseRestoresIdAndKeepsTtlUntouched() throws Exception {
        Message response = new Message(0);
        response.addRecord(new ARecord(Name.fromString("example.com."),
                DClass.IN, 300, InetAddress.getByName("192.0.2.1")), Section.ANSWER);
        byte[] wire = response.toWire();
        byte[] query = new byte[]{0x12, 0x34};

        // A fresh response (age 0) is finished by a direct byte patch, so the
        // wire message must be identical apart from the two transaction-ID bytes.
        DnsOverHttpsClient.restoreTransactionId(wire, query);

        Message restored = new Message(wire);
        assertEquals(0x1234, restored.getHeader().getID());
        assertEquals(300, restored.getSection(Section.ANSWER).get(0).getTTL());
    }

    @Test
    public void smallQueryUsesCacheFriendlyGet() {
        byte[] query = new byte[12];

        assertEquals("GET", DnsOverHttpsClient
                .buildRequest("https://dns.example/dns-query", query).method());
    }

    @Test
    public void oversizedGetFallsBackToPost() {
        byte[] query = new byte[4096];
        Arrays.fill(query, (byte) 1);

        assertEquals("POST", DnsOverHttpsClient
                .buildRequest("https://dns.example/dns-query", query).method());
    }
}
