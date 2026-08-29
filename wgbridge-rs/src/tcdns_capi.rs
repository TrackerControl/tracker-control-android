//! C ABI for applying the shared DNS response policy from the native packet
//! path. The caller owns the message buffer; callback strings are owned by
//! this library and remain valid only for the duration of their callback.

// Carried over from tc-dns, which denies these crate-wide: this module holds
// the FFI boundary, and the C contract requires it never to panic (the Android
// release profile builds with `panic = "abort"`).
#![cfg_attr(
    not(test),
    deny(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing
    )
)]

use std::ffi::{c_char, c_void, CString};

use tcdns::{process_response, DnsPolicy, Outcome};

pub const TCDNS_ABI_VERSION: u32 = 1;
pub const TCDNS_UNCHANGED: usize = usize::MAX;

pub type RecordAnswer = unsafe extern "C" fn(
    ctx: *mut c_void,
    qname: *const c_char,
    aname: *const c_char,
    resource: *const c_char,
    ttl: i32,
);
pub type IsDomainBlocked = unsafe extern "C" fn(ctx: *mut c_void, qname: *const c_char) -> i32;
pub type BlockedRcode = unsafe extern "C" fn(ctx: *mut c_void) -> u8;
pub type OnBlanked =
    unsafe extern "C" fn(ctx: *mut c_void, qname: *const c_char, qtype: u16, rcode: u8);
pub type Log = unsafe extern "C" fn(ctx: *mut c_void, priority: i32, msg: *const c_char);

#[repr(C)]
pub struct TcdnsCallbacks {
    pub abi_version: u32,
    pub record_answer: Option<RecordAnswer>,
    pub is_domain_blocked: Option<IsDomainBlocked>,
    pub blocked_rcode: Option<BlockedRcode>,
    pub on_blanked: Option<OnBlanked>,
    pub log: Option<Log>,
}

impl TcdnsCallbacks {
    fn is_valid(&self) -> bool {
        self.abi_version == TCDNS_ABI_VERSION
            && self.record_answer.is_some()
            && self.is_domain_blocked.is_some()
            && self.blocked_rcode.is_some()
            && self.on_blanked.is_some()
    }
}

struct CapiPolicy<'a> {
    callbacks: &'a TcdnsCallbacks,
    ctx: *mut c_void,
}

impl DnsPolicy for CapiPolicy<'_> {
    fn record_answer(&self, qname: &str, aname: &str, resource: &str, ttl: i32) {
        let Some(qname) = CString::new(qname).ok() else {
            return;
        };
        let Some(aname) = CString::new(aname).ok() else {
            return;
        };
        let Some(resource) = CString::new(resource).ok() else {
            return;
        };
        if let Some(record_answer) = self.callbacks.record_answer {
            // SAFETY: callback validity is checked before processing; all
            // CString pointers remain valid for this call only.
            unsafe {
                record_answer(
                    self.ctx,
                    qname.as_ptr(),
                    aname.as_ptr(),
                    resource.as_ptr(),
                    ttl,
                );
            }
        }
    }

    fn is_domain_blocked(&self, qname: &str) -> bool {
        let Some(qname) = CString::new(qname).ok() else {
            return false;
        };
        if let Some(is_domain_blocked) = self.callbacks.is_domain_blocked {
            // SAFETY: callback validity is checked before processing and the
            // CString pointer is valid for the duration of this call.
            unsafe { is_domain_blocked(self.ctx, qname.as_ptr()) != 0 }
        } else {
            false
        }
    }

    fn blocked_rcode(&self) -> u8 {
        if let Some(blocked_rcode) = self.callbacks.blocked_rcode {
            // SAFETY: callback validity is checked before processing.
            unsafe { blocked_rcode(self.ctx) }
        } else {
            3
        }
    }
}

/// Returns the C ABI version supported by this library.
#[no_mangle]
pub extern "C" fn tcdns_abi_version() -> u32 {
    TCDNS_ABI_VERSION
}

/// Processes one bare DNS message in place. See `tcdns.h` for the complete
/// pointer and callback lifetime contract.
///
/// # Safety
///
/// `data` must be writable for `len` bytes when `len` is nonzero, `callbacks`
/// must point to a valid callback table for the duration of the call, and all
/// callbacks must obey the non-reentrancy and no-panic contract.
#[no_mangle]
pub unsafe extern "C" fn tcdns_process_response(
    data: *mut u8,
    len: usize,
    callbacks: *const TcdnsCallbacks,
    ctx: *mut c_void,
) -> usize {
    let Some(callbacks) = callbacks.as_ref() else {
        return TCDNS_UNCHANGED;
    };
    if !callbacks.is_valid() || (len != 0 && data.is_null()) {
        return TCDNS_UNCHANGED;
    }
    let message = if len == 0 {
        &mut []
    } else {
        // SAFETY: the C contract requires `data` to be writable for `len`
        // bytes, and null was rejected above.
        std::slice::from_raw_parts_mut(data, len)
    };
    let policy = CapiPolicy { callbacks, ctx };
    match process_response(message, &policy) {
        Outcome::Unchanged => TCDNS_UNCHANGED,
        Outcome::Blanked {
            new_len,
            qname,
            qtype,
            rcode,
        } => {
            if let (Some(on_blanked), Some(qname)) =
                (callbacks.on_blanked, CString::new(qname).ok())
            {
                // SAFETY: callback validity is checked above and the CString
                // pointer remains valid for this callback.
                on_blanked(ctx, qname.as_ptr(), qtype, rcode);
            }
            new_len
        }
    }
}
