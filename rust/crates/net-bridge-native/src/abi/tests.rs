//! C ABI 布局与契约测试。

use std::mem::{offset_of, size_of};
use std::ptr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use super::api_v1::netbridge_get_api;
use super::status::*;
use super::types::*;

static TEST_EVENT_COUNT: AtomicUsize = AtomicUsize::new(0);

unsafe extern "C" fn test_on_event(_kind: u32, _obj: u64, _a0: i64, _a1: i64) {
    TEST_EVENT_COUNT.fetch_add(1, Ordering::SeqCst);
}

#[test]
fn abi_struct_sizes_and_offsets() {
    // 验证基本字段偏移和尺寸
    assert_eq!(size_of::<NbBytesViewV1>(), 16);
    assert_eq!(offset_of!(NbBytesViewV1, data), 0);
    assert_eq!(offset_of!(NbBytesViewV1, length), 8);

    assert_eq!(size_of::<NbSocketAddressV1>(), 32);
    assert_eq!(offset_of!(NbSocketAddressV1, family), 0);
    assert_eq!(offset_of!(NbSocketAddressV1, port), 4);
    assert_eq!(offset_of!(NbSocketAddressV1, address), 8);
    assert_eq!(offset_of!(NbSocketAddressV1, scope_id), 24);

    assert_eq!(size_of::<NbContextOptionsV1>(), 48);
    assert_eq!(offset_of!(NbContextOptionsV1, struct_size), 0);
    assert_eq!(offset_of!(NbContextOptionsV1, worker_threads), 8);

    assert_eq!(size_of::<NbCallbacksV1>(), 48);
    assert_eq!(offset_of!(NbCallbacksV1, on_event), 8);

    assert_eq!(size_of::<NbConnectOptionsV1>(), 72);
    assert_eq!(offset_of!(NbConnectOptionsV1, struct_size), 0);
    assert_eq!(offset_of!(NbConnectOptionsV1, transport_kind), 4);
    assert_eq!(offset_of!(NbConnectOptionsV1, host_utf8), 8);

    assert_eq!(size_of::<NbServerOptionsV1>(), 80);
    assert_eq!(offset_of!(NbServerOptionsV1, struct_size), 0);
    assert_eq!(offset_of!(NbServerOptionsV1, transport_kind), 4);
    assert_eq!(offset_of!(NbServerOptionsV1, bind_host_utf8), 8);

    assert_eq!(size_of::<NbApiV1>(), 184);
    assert_eq!(offset_of!(NbApiV1, abi_major), 0);
    assert_eq!(offset_of!(NbApiV1, abi_minor), 4);
    assert_eq!(offset_of!(NbApiV1, struct_size), 8);
    assert_eq!(offset_of!(NbApiV1, feature_bits), 16);
    assert_eq!(offset_of!(NbApiV1, context_create), 24);
    assert_eq!(offset_of!(NbApiV1, connect), 48);
    assert_eq!(offset_of!(NbApiV1, connection_write), 72);
    assert_eq!(offset_of!(NbApiV1, server_start), 96);
}

#[test]
fn get_api_bootstrap_negotiation() {
    let mut api_ptr: *const NbApiV1 = ptr::null();

    // 正常获取
    let res = unsafe { netbridge_get_api(1, 0, &mut api_ptr) };
    assert_eq!(res, NB_OK);
    assert!(!api_ptr.is_null());

    let api = unsafe { &*api_ptr };
    assert_eq!(api.abi_major, 1);
    assert_eq!(api.abi_minor, 0);
    assert_eq!(api.struct_size, size_of::<NbApiV1>() as u32);

    // major 不匹配
    let mut bad_ptr: *const NbApiV1 = ptr::null();
    let res_major = unsafe { netbridge_get_api(2, 0, &mut bad_ptr) };
    assert_eq!(res_major, NB_ABI_MISMATCH);

    // minor 过高
    let res_minor = unsafe { netbridge_get_api(1, 99, &mut bad_ptr) };
    assert_eq!(res_minor, NB_ABI_MISMATCH);

    // null 参数
    let res_null = unsafe { netbridge_get_api(1, 0, ptr::null_mut()) };
    assert_eq!(res_null, NB_INVALID_ARGUMENT);
}

#[test]
fn c_abi_quic_loopback_roundtrip() {
    let mut api_ptr: *const NbApiV1 = ptr::null();
    assert_eq!(unsafe { netbridge_get_api(1, 0, &mut api_ptr) }, NB_OK);
    let api = unsafe { &*api_ptr };

    // 1. 创建 Context
    let callbacks = NbCallbacksV1 {
        struct_size: size_of::<NbCallbacksV1>() as u32,
        reserved0: 0,
        on_event: Some(test_on_event),
        reserved: [0; 4],
    };
    let mut ctx: *mut NbContext = ptr::null_mut();
    let res_ctx = unsafe { (api.context_create.unwrap())(ptr::null(), &callbacks, &mut ctx) };
    assert_eq!(res_ctx, NB_OK);
    assert!(!ctx.is_null());

    // 2. 启动服务端
    let server_opts = NbServerOptionsV1 {
        struct_size: size_of::<NbServerOptionsV1>() as u32,
        transport_kind: NB_TRANSPORT_QUIC,
        bind_host_utf8: NbBytesViewV1 {
            data: ptr::null(),
            length: 0,
            reserved0: 0,
        },
        port: 0,
        reserved0: 0,
        max_connections: 64,
        kcp_profile: 0,
        flags: 0,
        reserved1: 0,
        reserved: [0; 4],
    };
    let mut server_id: u64 = 0;
    assert_eq!(
        unsafe { (api.server_start.unwrap())(ctx, &server_opts, &mut server_id) },
        NB_OK
    );
    assert_ne!(server_id, 0);

    let mut port: u16 = 0;
    assert_eq!(
        unsafe { (api.server_port.unwrap())(ctx, server_id, &mut port) },
        NB_OK
    );
    assert_ne!(port, 0);

    // 3. 客户端发起连接
    let host = b"127.0.0.1";
    let connect_opts = NbConnectOptionsV1 {
        struct_size: size_of::<NbConnectOptionsV1>() as u32,
        transport_kind: NB_TRANSPORT_QUIC,
        host_utf8: NbBytesViewV1 {
            data: host.as_ptr(),
            length: host.len() as u32,
            reserved0: 0,
        },
        port,
        reserved0: 0,
        kcp_profile: 0,
        flags: 0,
        reserved: [0; 4],
    };
    let mut client_id: u64 = 0;
    assert_eq!(
        unsafe { (api.connect.unwrap())(ctx, &connect_opts, &mut client_id) },
        NB_OK
    );
    assert_ne!(client_id, 0);

    // 4. 等待连接成功
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let mut state: u32 = 0;
        let s_res = unsafe { (api.connection_state.unwrap())(ctx, client_id, &mut state) };
        if s_res == NB_OK && state == NB_CONNECTION_CONNECTED {
            break;
        }
        assert!(Instant::now() < deadline, "timeout waiting for connection");
        std::thread::sleep(Duration::from_millis(10));
    }

    // 5. 写入与读取
    let msg = b"hello c abi ffm poc";
    let mut written: u32 = 0;
    assert_eq!(
        unsafe {
            (api.connection_write.unwrap())(
                ctx,
                client_id,
                msg.as_ptr(),
                msg.len() as u32,
                &mut written,
            )
        },
        NB_OK
    );
    assert_eq!(written, msg.len() as u32);

    // 6. 关闭与销毁
    assert_eq!(
        unsafe { (api.connection_close.unwrap())(ctx, client_id) },
        NB_OK
    );
    assert_eq!(unsafe { (api.server_stop.unwrap())(ctx, server_id) }, NB_OK);
    assert_eq!(unsafe { (api.context_shutdown.unwrap())(ctx, 2000) }, NB_OK);
    assert_eq!(unsafe { (api.context_destroy.unwrap())(ctx) }, NB_OK);
}

#[test]
fn prefix_safe_options_and_reserved_zero_validation() {
    let mut api_ptr: *const NbApiV1 = ptr::null();
    assert_eq!(unsafe { netbridge_get_api(1, 0, &mut api_ptr) }, NB_OK);
    let api = unsafe { &*api_ptr };

    // 1. Min prefix callback works
    #[repr(C)]
    struct MinCallbacks {
        struct_size: u32,
        reserved0: u32,
        on_event: NbEventCallbackV1,
    }
    let min_cb = MinCallbacks {
        struct_size: 16,
        reserved0: 0,
        on_event: Some(test_on_event),
    };
    let mut ctx: *mut NbContext = ptr::null_mut();
    assert_eq!(
        unsafe { (api.context_create.unwrap())(ptr::null(), min_cb_ptr(&min_cb), &mut ctx) },
        NB_OK
    );
    assert!(!ctx.is_null());

    // 2. Nonzero reserved callbacks rejected
    let bad_cb = MinCallbacks {
        struct_size: 16,
        reserved0: 1, // Nonzero!
        on_event: Some(test_on_event),
    };
    let mut bad_ctx: *mut NbContext = ptr::null_mut();
    assert_eq!(
        unsafe { (api.context_create.unwrap())(ptr::null(), min_cb_ptr(&bad_cb), &mut bad_ctx) },
        NB_INVALID_ARGUMENT
    );

    // 3. Min prefix server options works
    #[repr(C)]
    struct MinServerOptions {
        struct_size: u32,
        transport_kind: u32,
        bind_host_utf8: NbBytesViewV1,
        port: u16,
        reserved0: u16,
        max_connections: u32,
        kcp_profile: u32,
        flags: u32,
        reserved1: u32,
    }
    let min_srv = MinServerOptions {
        struct_size: 48,
        transport_kind: NB_TRANSPORT_QUIC,
        bind_host_utf8: NbBytesViewV1 {
            data: b"127.0.0.1".as_ptr(),
            length: 9,
            reserved0: 0,
        },
        port: 0,
        reserved0: 0,
        max_connections: 4,
        kcp_profile: 0,
        flags: 0,
        reserved1: 0,
    };
    let mut srv_id: u64 = 0;
    assert_eq!(
        unsafe { (api.server_start.unwrap())(ctx, min_srv_ptr(&min_srv), &mut srv_id,) },
        NB_OK
    );
    assert_ne!(srv_id, 0);

    // 4. Nonzero reserved bytes view rejected
    let bad_srv = MinServerOptions {
        struct_size: 48,
        transport_kind: NB_TRANSPORT_QUIC,
        bind_host_utf8: NbBytesViewV1 {
            data: b"127.0.0.1".as_ptr(),
            length: 9,
            reserved0: 1, // Nonzero!
        },
        port: 0,
        reserved0: 0,
        max_connections: 4,
        kcp_profile: 0,
        flags: 0,
        reserved1: 0,
    };
    let mut bad_srv_id: u64 = 0;
    assert_eq!(
        unsafe { (api.server_start.unwrap())(ctx, min_srv_ptr(&bad_srv), &mut bad_srv_id,) },
        NB_INVALID_ARGUMENT
    );

    unsafe {
        (api.server_stop.unwrap())(ctx, srv_id);
        (api.context_shutdown.unwrap())(ctx, 1000);
        (api.context_destroy.unwrap())(ctx);
    }
}

fn min_cb_ptr(p: &impl Sized) -> *const NbCallbacksV1 {
    p as *const _ as *const NbCallbacksV1
}

fn min_srv_ptr(p: &impl Sized) -> *const NbServerOptionsV1 {
    p as *const _ as *const NbServerOptionsV1
}
