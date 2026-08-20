//! Callback abstractions implemented by the JNI layer on Android and by
//! plain Rust fakes in tests.

/// Wraps VpnService.protect(fd). Must return true if the socket was
/// protected; the tunnel refuses to come up otherwise (fail closed).
pub trait SocketProtector: Send + Sync + 'static {
    fn protect(&self, fd: i32) -> bool;
}

/// Receives DNS answers observed on decrypted inbound packets and exposes the
/// DNS policy used when the response is sent back to the app.
pub trait DnsSink: Send + Sync + 'static {
    fn record_dns(&self, qname: &str, aname: &str, resource: &str, ttl: i32);

    /// Whether a response for `qname` should be returned without answers.
    ///
    /// The current Android callback deliberately returns `false`, matching
    /// ServiceSinkhole.isDomainBlocked on the master branch. Keeping this as
    /// an explicit policy hook lets the packet rewriter preserve the native
    /// path's semantics without inventing a second blocklist in Rust.
    fn is_domain_blocked(&self, _qname: &str) -> bool {
        false
    }

    /// RCODE used for a response blanked by the DNS policy. DNS RCODE is four
    /// bits; invalid callback values are clamped by the packet rewriter.
    fn blocked_rcode(&self) -> u8 {
        3 // NXDOMAIN, matching the native default preference.
    }
}

/// Bridge-level log lines destined for the Java side.
pub trait BridgeLogger: Send + Sync + 'static {
    fn verbose(&self, msg: &str);
    fn error(&self, msg: &str);
}

/// No-op logger for when the Java side passes null.
pub struct NullLogger;

impl BridgeLogger for NullLogger {
    fn verbose(&self, _msg: &str) {}
    fn error(&self, _msg: &str) {}
}
