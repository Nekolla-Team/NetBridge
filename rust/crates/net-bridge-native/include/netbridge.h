#ifndef NETBRIDGE_H
#define NETBRIDGE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifdef _WIN32
#define NB_EXPORT __declspec(dllexport)
#else
#define NB_EXPORT __attribute__((visibility("default")))
#endif
#define NB_API NB_EXPORT

/* === ABI Version === */
#define NB_ABI_MAJOR 1
#define NB_ABI_MINOR 0

/* === Base Types === */
typedef int32_t  nb_status_t;
typedef uint64_t nb_connection_t;
typedef uint64_t nb_server_t;
typedef struct nb_context nb_context_t;

/* === Status Codes === */
#define NB_OK                 0
#define NB_WOULD_BLOCK        1
#define NB_NOT_FOUND          2
#define NB_CLOSED             3
#define NB_INVALID_ARGUMENT   4
#define NB_INVALID_STATE      5
#define NB_ABI_MISMATCH       6
#define NB_NATIVE_UNAVAILABLE 7
#define NB_BIND_FAILED        8
#define NB_DNS_FAILED         9
#define NB_CONNECT_FAILED     10
#define NB_SHUTTING_DOWN      11
#define NB_TIMEOUT            12
#define NB_INTERNAL           13
#define NB_PANIC              14
#define NB_UNSUPPORTED        15

/* === Transport Kinds === */
#define NB_TRANSPORT_QUIC 1
#define NB_TRANSPORT_KCP  2

/* === Connection States === */
#define NB_CONNECTION_CONNECTING 1
#define NB_CONNECTION_CONNECTED  2
#define NB_CONNECTION_CLOSED     3
#define NB_CONNECTION_FAILED     4

/* === Event Kinds === */
#define NB_EVENT_CONNECTION_STATE 1
#define NB_EVENT_DATA_AVAILABLE   2
#define NB_EVENT_WRITABLE         3
#define NB_EVENT_ACCEPTED         4
#define NB_EVENT_SERVER_STATE     5

/* === Feature Bits === */
#define NB_FEATURE_QUIC                  (1ULL << 0)
#define NB_FEATURE_KCP                   (1ULL << 1)
#define NB_FEATURE_WRITABLE_EVENT        (1ULL << 2)
#define NB_FEATURE_BINARY_SOCKET_ADDRESS (1ULL << 3)
#define NB_FEATURE_SERVER_STATE_EVENT    (1ULL << 4)

/* === Struct Definitions === */

typedef struct nb_bytes_view_v1 {
    const uint8_t *data;
    uint32_t length;
    uint32_t reserved0;
} nb_bytes_view_v1_t;

typedef struct nb_socket_address_v1 {
    uint32_t family;      /* 4 or 6 */
    uint16_t port;        /* host byte order */
    uint16_t reserved0;
    uint8_t  address[16];
    uint32_t scope_id;    /* IPv6 scope, IPv4 = 0 */
    uint32_t reserved1;
} nb_socket_address_v1_t;

typedef struct nb_context_options_v1 {
    uint32_t struct_size;
    uint32_t flags;
    uint32_t worker_threads;     /* 0 = auto/default */
    uint32_t reserved0;
    uint64_t reserved[4];
} nb_context_options_v1_t;

typedef void (*nb_event_callback_v1)(
    uint32_t event_kind,
    uint64_t object_id,
    int64_t arg0,
    int64_t arg1
);

typedef struct nb_callbacks_v1 {
    uint32_t struct_size;
    uint32_t reserved0;
    nb_event_callback_v1 on_event;
    uint64_t reserved[4];
} nb_callbacks_v1_t;

typedef struct nb_connect_options_v1 {
    uint32_t struct_size;
    uint32_t transport_kind;
    nb_bytes_view_v1_t host_utf8;
    uint16_t port;
    uint16_t reserved0;
    uint32_t kcp_profile;
    uint32_t flags;
    uint64_t reserved[4];
} nb_connect_options_v1_t;

typedef struct nb_server_options_v1 {
    uint32_t struct_size;
    uint32_t transport_kind;
    nb_bytes_view_v1_t bind_host_utf8;  /* length=0 -> unspecified */
    uint16_t port;
    uint16_t reserved0;
    uint32_t max_connections;
    uint32_t kcp_profile;
    uint32_t flags;
    uint32_t reserved1;
    uint64_t reserved[4];
} nb_server_options_v1_t;

typedef struct nb_api_v1 {
    uint32_t abi_major;
    uint32_t abi_minor;
    uint32_t struct_size;
    uint32_t reserved0;
    uint64_t feature_bits;

    nb_status_t (*context_create)(
        const nb_context_options_v1_t *options,
        const nb_callbacks_v1_t *callbacks,
        nb_context_t **out_context
    );

    nb_status_t (*context_shutdown)(
        nb_context_t *context,
        uint32_t timeout_millis
    );

    nb_status_t (*context_destroy)(
        nb_context_t *context
    );

    nb_status_t (*connect)(
        nb_context_t *context,
        const nb_connect_options_v1_t *options,
        nb_connection_t *out_connection
    );

    nb_status_t (*connection_state)(
        nb_context_t *context,
        nb_connection_t connection,
        uint32_t *out_state
    );

    nb_status_t (*connection_remote_address)(
        nb_context_t *context,
        nb_connection_t connection,
        nb_socket_address_v1_t *out_address
    );

    nb_status_t (*connection_write)(
        nb_context_t *context,
        nb_connection_t connection,
        const uint8_t *data,
        uint32_t length,
        uint32_t *out_written
    );

    nb_status_t (*connection_read)(
        nb_context_t *context,
        nb_connection_t connection,
        uint8_t *data,
        uint32_t capacity,
        uint32_t *out_read
    );

    nb_status_t (*connection_close)(
        nb_context_t *context,
        nb_connection_t connection
    );

    nb_status_t (*server_start)(
        nb_context_t *context,
        const nb_server_options_v1_t *options,
        nb_server_t *out_server
    );

    nb_status_t (*server_port)(
        nb_context_t *context,
        nb_server_t server,
        uint16_t *out_port
    );

    nb_status_t (*server_stop)(
        nb_context_t *context,
        nb_server_t server
    );

    uint64_t reserved[8];
} nb_api_v1_t;

/* === Bootstrap Export === */
NB_EXPORT nb_status_t netbridge_get_api(
    uint32_t requested_major,
    uint32_t minimum_minor,
    const nb_api_v1_t **out_api
);

#ifdef __cplusplus
}
#endif

#endif /* NETBRIDGE_H */
