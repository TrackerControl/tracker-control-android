//! Callback abstractions implemented by the JNI layer on Android and by
//! plain Rust fakes in tests.

/// Wraps VpnService.protect(fd). Must return true if the socket was
/// protected; the tunnel refuses to come up otherwise (fail closed).
pub trait SocketProtector: Send + Sync + 'static {
    fn protect(&self, fd: i32) -> bool;
}

/// Receives DNS answers observed on decrypted inbound packets. Passive:
/// TrackerControl uses this mapping later when deciding on app connections,
/// but the DNS response is not blocked or rewritten here.
pub trait DnsSink: Send + Sync + 'static {
    fn record_dns(&self, qname: &str, aname: &str, resource: &str, ttl: i32);
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
