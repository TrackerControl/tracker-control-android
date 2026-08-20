/// Policy callbacks used by the DNS message parser and rewriter.
pub trait DnsPolicy {
    /// Records one address answer before a response is potentially blanked.
    fn record_answer(&self, qname: &str, aname: &str, resource: &str, ttl: i32);

    /// Returns whether a response for `qname` should be returned without
    /// answers. The default leaves ordinary DNS responses unchanged.
    fn is_domain_blocked(&self, _qname: &str) -> bool {
        false
    }

    /// RCODE used for a response blanked by policy. Only the low four bits
    /// are meaningful and are masked by the message layer.
    fn blocked_rcode(&self) -> u8 {
        3
    }
}
