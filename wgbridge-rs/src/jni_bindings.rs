//! JNI entry points backing the hand-written Java classes in
//! net.kollnig.missioncontrol.wgbridge (Wgbridge, Tunnel) and the Java-side
//! callback interfaces (Protector, Logger, DnsRecorder).

use std::sync::Arc;
use std::sync::Once;

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, jlongArray, jstring};
use jni::{JNIEnv, JavaVM};

use crate::callbacks::{BridgeLogger, DnsSink, NullLogger, SocketProtector};
use crate::keys;
use crate::tunnel::{start_tunnel, Tunnel};

static LOGGER_INIT: Once = Once::new();

fn init_android_logger() {
    LOGGER_INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("wgbridge"),
        );
    });
}

fn throw(env: &mut JNIEnv, msg: &str) {
    // Don't clobber an exception already in flight.
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new("java/lang/RuntimeException", msg);
    }
}

fn get_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|s| s.into())
}

struct JavaCallback {
    vm: JavaVM,
    obj: GlobalRef,
}

impl JavaCallback {
    fn new(env: &JNIEnv, obj: JObject) -> Option<Self> {
        if obj.is_null() {
            return None;
        }
        let vm = env.get_java_vm().ok()?;
        let obj = env.new_global_ref(obj).ok()?;
        Some(Self { vm, obj })
    }

    /// Runs `f` with a JNIEnv attached as a daemon. Worker threads stay
    /// attached for their lifetime without keeping the JVM alive at shutdown.
    fn with_env<R>(
        &self,
        f: impl FnOnce(&mut JNIEnv, &GlobalRef) -> jni::errors::Result<R>,
    ) -> Option<R> {
        let mut env = self.vm.attach_current_thread_as_daemon().ok()?;
        match f(&mut env, &self.obj) {
            Ok(r) => Some(r),
            Err(_) => {
                let _ = env.exception_clear();
                None
            }
        }
    }
}

struct JavaProtector(JavaCallback);

impl SocketProtector for JavaProtector {
    fn protect(&self, fd: i32) -> bool {
        self.0
            .with_env(|env, obj| {
                env.call_method(obj, "protect", "(I)Z", &[JValue::Int(fd)])?
                    .z()
            })
            .unwrap_or(false)
    }
}

struct JavaLogger(JavaCallback);

impl BridgeLogger for JavaLogger {
    fn verbose(&self, msg: &str) {
        self.0.with_env(|env, obj| {
            let s = env.new_string(msg)?;
            env.call_method(
                obj,
                "verbosef",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&s)],
            )
            .map(|_| ())
        });
    }

    fn error(&self, msg: &str) {
        self.0.with_env(|env, obj| {
            let s = env.new_string(msg)?;
            env.call_method(obj, "errorf", "(Ljava/lang/String;)V", &[JValue::Object(&s)])
                .map(|_| ())
        });
    }
}

struct JavaDnsSink(JavaCallback);

impl DnsSink for JavaDnsSink {
    fn record_dns(&self, qname: &str, aname: &str, resource: &str, ttl: i32) {
        self.0.with_env(|env, obj| {
            let q = env.new_string(qname)?;
            let a = env.new_string(aname)?;
            let r = env.new_string(resource)?;
            env.call_method(
                obj,
                "recordDns",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
                &[
                    JValue::Object(&q),
                    JValue::Object(&a),
                    JValue::Object(&r),
                    JValue::Int(ttl),
                ],
            )
            .map(|_| ())
        });
    }
}

fn tunnel_from_handle<'a>(handle: jlong) -> Option<&'a Tunnel> {
    if handle == 0 {
        return None;
    }
    // SAFETY: handle is a Box::into_raw pointer created by nativeStartTunnel
    // and not yet freed by nativeStop (the Java Tunnel class guarantees
    // single ownership and zeroes its handle on stop).
    Some(unsafe { &*(handle as *const Tunnel) })
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Wgbridge_generatePrivateKey(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match keys::generate_private_key() {
        Ok(key) => env
            .new_string(key)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Wgbridge_publicKey(
    mut env: JNIEnv,
    _class: JClass,
    private_key: JString,
) -> jstring {
    let Some(private_key) = get_string(&mut env, &private_key) else {
        throw(&mut env, "privateKey must not be null");
        return std::ptr::null_mut();
    };
    match keys::public_key(&private_key) {
        Ok(key) => env
            .new_string(key)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Wgbridge_nativeStartTunnel(
    mut env: JNIEnv,
    _class: JClass,
    uapi_config: JString,
    outbound_rx_fd: jint,
    tun_write_fd: jint,
    mtu: jint,
    protector: JObject,
    logger: JObject,
    dns_recorder: JObject,
) -> jlong {
    init_android_logger();

    let Some(uapi) = get_string(&mut env, &uapi_config) else {
        throw(&mut env, "uapiConfig must not be null");
        return 0;
    };
    let Some(protector) = JavaCallback::new(&env, protector) else {
        throw(&mut env, "protector must not be null");
        return 0;
    };
    let logger: Arc<dyn BridgeLogger> = match JavaCallback::new(&env, logger) {
        Some(cb) => Arc::new(JavaLogger(cb)),
        None => Arc::new(NullLogger),
    };
    let dns: Option<Arc<dyn DnsSink>> =
        JavaCallback::new(&env, dns_recorder).map(|cb| Arc::new(JavaDnsSink(cb)) as _);

    // Attach each tokio worker to the JVM as a daemon as it spins up, so the Java
    // GlobalRefs the callbacks hold are dropped on attached threads at
    // teardown (avoids the jni "detached thread" GlobalRef warning).
    let Ok(vm) = env.get_java_vm() else {
        throw(&mut env, "could not obtain JavaVM");
        return 0;
    };
    let on_worker_start: Arc<dyn Fn() + Send + Sync> = Arc::new(move || {
        let _ = vm.attach_current_thread_as_daemon();
    });

    match start_tunnel(
        &uapi,
        outbound_rx_fd,
        tun_write_fd,
        mtu.clamp(576, 65535) as u16,
        Arc::new(JavaProtector(protector)),
        logger,
        dns,
        on_worker_start,
    ) {
        Ok(tunnel) => Box::into_raw(Box::new(tunnel)) as jlong,
        Err(e) => {
            throw(&mut env, &e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Tunnel_nativeSetConfig(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    uapi_config: JString,
) {
    let Some(tunnel) = tunnel_from_handle(handle) else {
        throw(&mut env, "tunnel stopped");
        return;
    };
    let Some(uapi) = get_string(&mut env, &uapi_config) else {
        throw(&mut env, "uapiConfig must not be null");
        return;
    };
    if let Err(e) = tunnel.set_config(&uapi) {
        throw(&mut env, &e);
    }
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Tunnel_nativeStats(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let Some(tunnel) = tunnel_from_handle(handle) else {
        throw(&mut env, "tunnel stopped");
        return std::ptr::null_mut();
    };
    match tunnel.stats() {
        Ok(stats) => {
            let values = [stats.rx_bytes, stats.tx_bytes, stats.latest_handshake_millis];
            match env.new_long_array(3) {
                Ok(array) => {
                    let _ = env.set_long_array_region(&array, 0, &values);
                    array.into_raw()
                }
                Err(e) => {
                    throw(&mut env, &e.to_string());
                    std::ptr::null_mut()
                }
            }
        }
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Tunnel_nativeSendKeepalive(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Some(tunnel) = tunnel_from_handle(handle) else {
        throw(&mut env, "tunnel stopped");
        return;
    };
    tunnel.send_keepalive();
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Tunnel_nativeRebind(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Some(tunnel) = tunnel_from_handle(handle) else {
        throw(&mut env, "tunnel stopped");
        return;
    };
    if let Err(e) = tunnel.rebind() {
        throw(&mut env, &e);
    }
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Tunnel_nativeUpdateEndpoint(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    public_key: JString,
    endpoint: JString,
) {
    let Some(tunnel) = tunnel_from_handle(handle) else {
        throw(&mut env, "tunnel stopped");
        return;
    };
    let (Some(public_key), Some(endpoint)) = (
        get_string(&mut env, &public_key),
        get_string(&mut env, &endpoint),
    ) else {
        throw(&mut env, "publicKey and endpoint must not be null");
        return;
    };
    let key = match keys::parse_public_key_b64(&public_key) {
        Ok(key) => key,
        Err(e) => {
            throw(&mut env, &e);
            return;
        }
    };
    if let Err(e) = tunnel.update_endpoint(&key, &endpoint) {
        throw(&mut env, &e);
    }
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Tunnel_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: reclaims the Box created by nativeStartTunnel. The Java side
    // zeroes its handle before calling, so this runs at most once.
    let tunnel = unsafe { Box::from_raw(handle as *mut Tunnel) };
    tunnel.stop();
    drop(tunnel);
}

/// Returns whether a `jboolean` from Java is true; kept for future natives.
#[allow(dead_code)]
fn jbool(b: jboolean) -> bool {
    b != 0
}
