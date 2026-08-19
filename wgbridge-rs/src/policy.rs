//! Per-app tunnel routing decision, shared between the C hijack path
//! (jni/netguard/policy.c) and this crate over a C ABI. Moving the decision
//! here gives it a `cargo test` harness; the C side becomes a thin caller.
//!
//! The route table is a process-global set of UID *overrides*: it is
//! deliberately not the full tunnelled set, because the C side only ever
//! pushes down UIDs whose routing differs from the current global default.
//! An app absent from the set follows the default, whatever it is — see
//! [`RouteTable::is_tunnel_uid`].

use std::sync::{PoisonError, RwLock};

/// Per-packet facts the tunnel decision is made from. Everything here is
/// cheap to compute on the C side before crossing the FFI boundary.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PacketFacts {
    /// Destination is loopback, link-local, or multicast.
    pub local_dest: bool,
    /// Port 53 traffic, over UDP or TCP.
    pub is_dns: bool,
    /// This packet's owning UID is routed through the tunnel.
    pub tunnel_uid: bool,
    /// Direct apps' DNS is being redirected to the system resolver.
    pub dns_direct: bool,
}

/// Decides whether a packet should be sent into the WireGuard tunnel.
///
/// Branch order matters and mirrors the C original:
/// 1. A local destination that isn't DNS can't meaningfully be forwarded by
///    WireGuard (it has no route back to loopback/link-local/multicast), so
///    it always goes direct.
/// 2. Otherwise, every DNS query defaults to the tunnel — this keeps
///    resolution consistent for tunnelled apps and is also what lets a
///    direct app's DNS be redirected to the tunnel's resolver when
///    `dns_direct` is off. It is checked before the per-UID decision.
/// 3. Anything left just follows the packet's own UID routing.
pub fn wants_tunnel(facts: PacketFacts) -> bool {
    if facts.local_dest && !facts.is_dns {
        return false;
    }
    if facts.is_dns && !facts.dns_direct {
        return true;
    }
    facts.tunnel_uid
}

/// A set of UIDs whose tunnel routing differs from `default_tunnel`, plus
/// the default itself. Absence from `uids` means "follows the default".
pub struct RouteTable {
    uids: Vec<i32>,
    default_tunnel: bool,
}

impl RouteTable {
    /// The initial/fallback table: no overrides, everything tunnelled. This
    /// is also the fail-safe state used when the global lock is poisoned.
    pub const fn tunnel_all() -> Self {
        RouteTable {
            uids: Vec::new(),
            default_tunnel: true,
        }
    }

    /// Builds a table from a UID slice, normalising it (sorted, deduped) so
    /// `is_tunnel_uid` can binary-search it.
    pub fn new(uids: &[i32], default_tunnel: bool) -> Self {
        let mut uids = uids.to_vec();
        uids.sort_unstable();
        uids.dedup();
        RouteTable {
            uids,
            default_tunnel,
        }
    }

    /// Whether `uid` is routed through the tunnel.
    ///
    /// Negative UIDs (kernel/no-owner packets) always follow the default:
    /// there is no per-app policy to look up for them. A UID present in the
    /// override set gets the *opposite* of the default; everything else,
    /// listed or not, follows the default unchanged.
    pub fn is_tunnel_uid(&self, uid: i32) -> bool {
        if uid < 0 {
            return self.default_tunnel;
        }
        match self.uids.binary_search(&uid) {
            Ok(_) => !self.default_tunnel,
            Err(_) => self.default_tunnel,
        }
    }

    /// True when this table carries any per-UID overrides at all.
    pub fn has_overrides(&self) -> bool {
        !self.uids.is_empty()
    }

    /// The global default routing for UIDs with no override.
    pub fn default_tunnel(&self) -> bool {
        self.default_tunnel
    }
}

/// Process-global route table, updated by the C side whenever the app's
/// per-app routing preferences change. `RwLock::new` is const, so this needs
/// no lazy initialisation.
static ROUTES: RwLock<RouteTable> = RwLock::new(RouteTable::tunnel_all());

/// ABI version for the C shim to sanity-check against. Bump on any breaking
/// change to the exported signatures below.
const POLICY_ABI_VERSION: i32 = 1;

#[no_mangle]
pub extern "C" fn tc_policy_abi_version() -> core::ffi::c_int {
    POLICY_ABI_VERSION
}

/// Replaces the global route table. `count <= 0` or a null `uids` is treated
/// as an empty override set rather than dereferenced.
///
/// # Safety
/// If `uids` is non-null, it must point to at least `count` valid `i32`s.
#[no_mangle]
pub extern "C" fn tc_policy_set_route_uids(
    uids: *const i32,
    count: core::ffi::c_int,
    default_tunnel: core::ffi::c_int,
) {
    let slice: &[i32] = if uids.is_null() || count <= 0 {
        &[]
    } else {
        // SAFETY: caller guarantees `uids` points to at least `count`
        // valid, initialised i32s when non-null and count > 0.
        unsafe { std::slice::from_raw_parts(uids, count as usize) }
    };
    let table = RouteTable::new(slice, default_tunnel != 0);
    let mut guard = ROUTES.write().unwrap_or_else(PoisonError::into_inner);
    *guard = table;
}

/// Drops all overrides and returns to tunnelling everything.
#[no_mangle]
pub extern "C" fn tc_policy_clear_route_uids() {
    let mut guard = ROUTES.write().unwrap_or_else(PoisonError::into_inner);
    *guard = RouteTable::tunnel_all();
}

#[no_mangle]
pub extern "C" fn tc_policy_is_tunnel_uid(uid: i32) -> core::ffi::c_int {
    // A poisoned lock still must answer, fail-safe towards tunnelling: a
    // panicked writer never leaves an inconsistent `RouteTable`, so reading
    // through the poison is safe and keeps the app in the tunnel by default.
    let guard = ROUTES.read().unwrap_or_else(PoisonError::into_inner);
    guard.is_tunnel_uid(uid) as core::ffi::c_int
}

#[no_mangle]
pub extern "C" fn tc_policy_wants_tunnel(
    local_dest: core::ffi::c_int,
    is_dns: core::ffi::c_int,
    tunnel_uid: core::ffi::c_int,
    dns_direct: core::ffi::c_int,
) -> core::ffi::c_int {
    let facts = PacketFacts {
        local_dest: local_dest != 0,
        is_dns: is_dns != 0,
        tunnel_uid: tunnel_uid != 0,
        dns_direct: dns_direct != 0,
    };
    wants_tunnel(facts) as core::ffi::c_int
}

#[cfg(test)]
mod tests {
    use super::*;

    fn facts(local_dest: bool, is_dns: bool, tunnel_uid: bool, dns_direct: bool) -> PacketFacts {
        PacketFacts {
            local_dest,
            is_dns,
            tunnel_uid,
            dns_direct,
        }
    }

    #[test]
    fn local_non_dns_never_tunnels() {
        for tunnel_uid in [false, true] {
            for dns_direct in [false, true] {
                assert!(!wants_tunnel(facts(true, false, tunnel_uid, dns_direct)));
            }
        }
    }

    #[test]
    fn local_dns_still_tunnels_when_dns_is_redirected() {
        // Surprising but intentional: a loopback-destined DNS query is still
        // handed to the tunnel when dns_direct is off. This is the existing
        // shipped behaviour (a local stub resolver forwarding onward), not a
        // bug introduced by this module — pinned here deliberately.
        assert!(wants_tunnel(facts(true, true, false, false)));
        assert!(wants_tunnel(facts(true, true, true, false)));
    }

    #[test]
    fn non_local_non_dns_follows_uid_routing() {
        assert!(wants_tunnel(facts(false, false, true, false)));
        assert!(!wants_tunnel(facts(false, false, false, false)));
        assert!(wants_tunnel(facts(false, false, true, true)));
        assert!(!wants_tunnel(facts(false, false, false, true)));
    }

    #[test]
    fn dns_without_direct_redirect_always_tunnels() {
        // Every resolver query takes the tunnel regardless of the packet's
        // own UID routing, as long as direct apps' DNS isn't being
        // redirected to the system resolver instead.
        assert!(wants_tunnel(facts(false, true, false, false)));
        assert!(wants_tunnel(facts(false, true, true, false)));
    }

    #[test]
    fn dns_with_direct_redirect_follows_uid_routing() {
        assert!(wants_tunnel(facts(false, true, true, true)));
        assert!(!wants_tunnel(facts(false, true, false, true)));
    }

    #[test]
    fn exhaustive_matches_three_branch_definition() {
        for local_dest in [false, true] {
            for is_dns in [false, true] {
                for tunnel_uid in [false, true] {
                    for dns_direct in [false, true] {
                        let f = facts(local_dest, is_dns, tunnel_uid, dns_direct);
                        // Written out independently of wants_tunnel's own
                        // implementation, so this is a real cross-check and
                        // not just calling the function on itself.
                        let expected = if local_dest && !is_dns {
                            false
                        } else if is_dns && !dns_direct {
                            true
                        } else {
                            tunnel_uid
                        };
                        assert_eq!(
                            wants_tunnel(f),
                            expected,
                            "mismatch for local_dest={local_dest} is_dns={is_dns} \
                             tunnel_uid={tunnel_uid} dns_direct={dns_direct}"
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn regression_default_mode_matches_pre_per_app_routing_behaviour() {
        // Before per-app routing existed, the decision was simply
        // `!local_dest || is_dns`. With tunnel_uid=true and dns_direct=false
        // (the default-mode combination), wants_tunnel must reduce to
        // exactly that expression for every local_dest/is_dns pair, proving
        // the default mode is byte-identical to the old behaviour.
        for local_dest in [false, true] {
            for is_dns in [false, true] {
                let f = facts(local_dest, is_dns, true, false);
                assert_eq!(wants_tunnel(f), !local_dest || is_dns);
            }
        }
    }

    #[test]
    fn empty_table_follows_default_both_directions() {
        let tunnel = RouteTable::new(&[], true);
        let direct = RouteTable::new(&[], false);
        assert!(tunnel.is_tunnel_uid(42));
        assert!(!direct.is_tunnel_uid(42));
        assert!(!tunnel.has_overrides());
        assert!(!direct.has_overrides());
    }

    #[test]
    fn negative_uid_follows_default_even_with_overrides() {
        let table = RouteTable::new(&[1, 2, 3], false);
        assert!(!table.is_tunnel_uid(-1));
        assert_eq!(table.is_tunnel_uid(i32::MIN), table.default_tunnel());
    }

    #[test]
    fn listed_uid_inverts_default_true() {
        let table = RouteTable::new(&[10, 20], true);
        assert!(!table.is_tunnel_uid(10));
        assert!(!table.is_tunnel_uid(20));
        assert!(table.is_tunnel_uid(30));
    }

    #[test]
    fn listed_uid_inverts_default_false() {
        let table = RouteTable::new(&[10, 20], false);
        assert!(table.is_tunnel_uid(10));
        assert!(table.is_tunnel_uid(20));
        assert!(!table.is_tunnel_uid(30));
    }

    #[test]
    fn unlisted_uid_in_nonempty_set_follows_default_not_absence_means_direct() {
        // Regression pin: an earlier version treated absence from the
        // pushed-down set as "direct", which sent a resolved-but-unlisted
        // UID (e.g. an app installed after the last reload) out of the
        // tunnel even when the global default was to tunnel everything.
        // Absence must always mean "follow the default", not "direct".
        let tunnel_default = RouteTable::new(&[7], true);
        assert!(tunnel_default.is_tunnel_uid(999));

        let direct_default = RouteTable::new(&[7], false);
        assert!(!direct_default.is_tunnel_uid(999));
    }

    #[test]
    fn unsorted_and_duplicate_input_is_normalised() {
        let a = RouteTable::new(&[5, 1, 5, 3, 1, 3], true);
        let b = RouteTable::new(&[1, 3, 5], true);
        assert!(a.has_overrides());
        assert!(b.has_overrides());
        for uid in [0, 1, 2, 3, 4, 5, 6] {
            assert_eq!(a.is_tunnel_uid(uid), b.is_tunnel_uid(uid));
        }
    }

    #[test]
    fn boundary_uids() {
        let table = RouteTable::new(&[0, i32::MAX], true);
        assert!(!table.is_tunnel_uid(0));
        assert!(!table.is_tunnel_uid(i32::MAX));
        // i32::MIN is negative, so it always follows the default rather
        // than being looked up in the set.
        assert!(table.is_tunnel_uid(i32::MIN));
        assert!(table.is_tunnel_uid(1));

        let single = RouteTable::new(&[42], false);
        assert!(single.is_tunnel_uid(42));
        assert!(!single.is_tunnel_uid(41));
        assert!(!single.is_tunnel_uid(43));
    }

    #[test]
    fn has_overrides_reflects_set_emptiness() {
        assert!(!RouteTable::new(&[], true).has_overrides());
        assert!(RouteTable::new(&[1], true).has_overrides());
        assert!(!RouteTable::tunnel_all().has_overrides());
    }

    // The FFI surface mutates a process-global (`ROUTES`), and `cargo test`
    // runs tests in parallel, so every other test above exercises the pure
    // RouteTable/wants_tunnel logic directly. This is the one test allowed
    // to touch the global, and it does so as a single ordered sequence.
    #[test]
    fn ffi_sequence_matches_direct_route_table_use() {
        assert_eq!(tc_policy_abi_version(), 1);

        // Set an override set with default_tunnel = false and check it
        // against an equivalent RouteTable built directly.
        let uids = [3i32, 1, 2, 1];
        let expected = RouteTable::new(&uids, false);
        tc_policy_set_route_uids(uids.as_ptr(), uids.len() as core::ffi::c_int, 0);
        for uid in [-5, 0, 1, 2, 3, 4, i32::MAX, i32::MIN] {
            assert_eq!(
                tc_policy_is_tunnel_uid(uid) != 0,
                expected.is_tunnel_uid(uid)
            );
        }

        // count = 0 with a real pointer yields an empty set.
        tc_policy_set_route_uids(uids.as_ptr(), 0, 1);
        assert_eq!(tc_policy_is_tunnel_uid(999) != 0, true);
        assert_eq!(tc_policy_is_tunnel_uid(1) != 0, true);

        // count < 0 yields an empty set too.
        tc_policy_set_route_uids(uids.as_ptr(), -1, 0);
        assert_eq!(tc_policy_is_tunnel_uid(1) != 0, false);

        // Null pointer with count > 0 must not be dereferenced, and also
        // yields an empty set.
        tc_policy_set_route_uids(std::ptr::null(), 5, 1);
        assert_eq!(tc_policy_is_tunnel_uid(123) != 0, true);

        // Re-apply overrides, then clear and confirm tunnel-everything.
        tc_policy_set_route_uids(uids.as_ptr(), uids.len() as core::ffi::c_int, 1);
        assert_eq!(tc_policy_is_tunnel_uid(1) != 0, false);
        tc_policy_clear_route_uids();
        assert_eq!(tc_policy_is_tunnel_uid(1) != 0, true);
        assert_eq!(tc_policy_is_tunnel_uid(-1) != 0, true);

        // wants_tunnel FFI wrapper returns literal 1/0 matching the direct
        // function across representative inputs.
        for local_dest in [0, 1] {
            for is_dns in [0, 1] {
                for tunnel_uid in [0, 1] {
                    for dns_direct in [0, 1] {
                        let want = wants_tunnel(facts(
                            local_dest != 0,
                            is_dns != 0,
                            tunnel_uid != 0,
                            dns_direct != 0,
                        ));
                        assert_eq!(
                            tc_policy_wants_tunnel(local_dest, is_dns, tunnel_uid, dns_direct),
                            want as core::ffi::c_int
                        );
                    }
                }
            }
        }
    }
}
