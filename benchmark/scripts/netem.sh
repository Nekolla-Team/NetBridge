#!/usr/bin/env bash
#
# netem.sh — idempotent WAN emulation for benchmark runs (root only).
#
# Creates an isolated network namespace "nbnet" with a veth pair and applies
# tc-netem inside it, so a benchmark server started inside the namespace
# experiences configurable RTT/loss/jitter on the client<->server path.
#
# Profiles:
#   wan      default: 60ms RTT / 0.5% loss / 5ms jitter
#   bad-wan  harsher: 120ms RTT / 2% loss / 10ms jitter
#
# Usage:
#   sudo benchmark/scripts/netem.sh <wan|bad-wan|--cleanup> [--rtt MS --loss PCT --jitter MS]
#                                                          [--direction asymmetric|both]
#
# Impairment is applied to host-side egress only by default (asymmetric, single
# direction). Pass --direction both to also impair namespace egress and emulate
# a symmetric path.
#
# The namespace persists until --cleanup (or reboot).
set -euo pipefail

NS="nbnet"
VETH_HOST="nb-veth0"
VETH_NS="nb-veth1"
HOST_CIDR="10.99.0.1/30"
NS_CIDR="10.99.0.2/30"
NS_IP="10.99.0.2"
HOST_IP="10.99.0.1"

rtt_ms=60
loss_pct=0.5
jitter_ms=5
direction="asymmetric"

cleanup() {
    ip netns del "$NS" 2>/dev/null || true
    ip link del "$VETH_HOST" 2>/dev/null || true
    echo "netem: namespace '$NS' and host veth removed"
}

require_tools() {
    for tool in ip tc; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            echo "netem: required tool '$tool' not found" >&2
            exit 1
        fi
    done
}

require_number() {
    local label="$1" value="$2"
    if ! [[ "$value" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        echo "netem: $label must be a non-negative number, got '$value'" >&2
        exit 2
    fi
}

# shellcheck disable=SC2015
case "${1:-wan}" in
    --cleanup|-c)
        cleanup
        exit 0
        ;;
    wan)
        ;;
    bad-wan)
        rtt_ms=120
        loss_pct=2
        jitter_ms=10
        ;;
    *)
        echo "usage: $0 <wan|bad-wan|--cleanup> [--rtt MS --loss PCT --jitter MS]" >&2
        exit 2
        ;;
esac

shift || true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --rtt)       rtt_ms="$2";    shift 2 ;;
        --loss)      loss_pct="$2";  shift 2 ;;
        --jitter)    jitter_ms="$2"; shift 2 ;;
        --direction) direction="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

require_number "rtt" "$rtt_ms"
require_number "loss" "$loss_pct"
require_number "jitter" "$jitter_ms"
case "$direction" in
    asymmetric|both) ;;
    *) echo "netem: --direction must be asymmetric|both, got '$direction'" >&2; exit 2 ;;
esac

require_tools

if [[ $(id -u) -ne 0 ]]; then
    echo "netem: must run as root (namespace + tc)" >&2
    exit 1
fi

# Roll back partial setup on any failure.
trap 'echo "netem: setup failed; rolling back" >&2; cleanup' ERR

# Idempotent: recreate if a stale namespace exists.
ip netns list | grep -q "^$NS" && cleanup

ip netns add "$NS"
ip link add "$VETH_HOST" type veth peer name "$VETH_NS"
ip link set "$VETH_NS" netns "$NS"
ip addr add "$HOST_CIDR" dev "$VETH_HOST"
ip link set "$VETH_HOST" up
ip netns exec "$NS" ip addr add "$NS_CIDR" dev "$VETH_NS"
ip netns exec "$NS" ip link set lo up
ip netns exec "$NS" ip link set "$VETH_NS" up
ip netns exec "$NS" ip route add default via "$HOST_IP"

# Emulate the WAN on the host egress path (into the namespace).
tc qdisc add dev "$VETH_HOST" root netem \
    delay "${rtt_ms}ms" "${jitter_ms}ms" distribution normal loss "${loss_pct}%"

if [[ "$direction" == "both" ]]; then
    # Symmetric emulation: also impair namespace egress toward the host.
    ip netns exec "$NS" tc qdisc add dev "$VETH_NS" root netem \
        delay "${rtt_ms}ms" "${jitter_ms}ms" distribution normal loss "${loss_pct}%"
fi

trap - ERR

echo "netem: namespace '$NS' ready (profile=$direction rtt=${rtt_ms}ms loss=${loss_pct}% jitter=${jitter_ms}ms)"
echo "  host : $HOST_CIDR on $VETH_HOST"
echo "  in-ns: $NS_CIDR on $VETH_NS"
echo "Example:"
echo "  ip netns exec $NS <server binary>   # benchmark server runs inside $NS"
echo "  # then point the client at the namespace-side server address $NS_IP"
echo "  # (a client on the host cannot route to $NS_IP directly; run it inside"
echo "  #  the namespace too, or add explicit forwarding/proxy)."
echo "Environment JSON fragment (record in the run environment):"
echo "  { \"networkProfile\": \"netem\", \"direction\": \"$direction\","
echo "    \"rttMillis\": $rtt_ms, \"jitterMillis\": $jitter_ms, \"lossPercent\": $loss_pct }"
echo "  Pass -Dnetbridge.benchmark.networkProfile=\"netem/$direction/${rtt_ms}ms/${loss_pct}%/${jitter_ms}ms\""
echo "  to the benchmark JVM to persist it in the environment manifest."
