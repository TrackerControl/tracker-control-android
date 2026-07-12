package net.kollnig.missioncontrol.dns;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

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
    public void restoresCallersTransactionId() {
        byte[] query = new byte[]{0x12, 0x34};
        byte[] response = new byte[]{0x00, 0x00, (byte) 0x81, (byte) 0x80};

        DnsOverHttpsClient.restoreTransactionId(query, response);

        assertArrayEquals(new byte[]{0x12, 0x34, (byte) 0x81, (byte) 0x80}, response);
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
