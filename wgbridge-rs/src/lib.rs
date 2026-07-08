//! Embeds gotatun (Mullvad's Rust WireGuard implementation) inside another
//! VpnService process. It hands gotatun a custom IP transport whose receive
//! side pulls outbound IP packets from a Unix socketpair fd that the C side
//! of TrackerControl (jni/netguard/ip.c) writes into when the WG hijack is
//! active. Inbound (decrypted) packets are written directly to the
//! VpnService TUN fd, so the C code never sees them.
//!
//! The outer UDP sockets that gotatun uses to talk to the peer are
//! VpnService.protect()-ed via a Java callback, otherwise their packets
//! would be re-captured by NetGuard's TUN and loop forever.
//!
//! Build:
//!
//! ```text
//! cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 -p 23 \
//!     -o ../app/build/rustJniLibs build --release
//! ```

pub mod callbacks;
pub mod config;
pub mod dns;
pub mod keys;
pub mod transport;
pub mod tunnel;

#[cfg(target_os = "android")]
mod jni_bindings;
