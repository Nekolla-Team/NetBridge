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
#
# The namespace persists until --cleanup (or reboot).
set -euo pipefail

NS="nbnet"
VETH_HOST="nb-veth0"
VETH_NS="nb-veth1"
ADDR_NET="10.99.0.0/30"

rtt_ms=60
loss_pct=0.5
jitter_ms=5

cleanup() {
    ip netns del "$NS" 2>/dev/null || true
    echo "netem: namespace '$NS' removed"
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
        --rtt)    rtt_ms="$2";    shift 2 ;;
        --loss)   loss_pct="$2";  shift 2 ;;
        --jitter) jitter_ms="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

if [[ $(id -u) -ne 0 ]]; then
    echo "netem: must run as root (namespace + tc)" >&2
    exit 1
fi

# Idempotent: recreate if a stale namespace exists.
ip netns list | grep -q "^$NS" && cleanup

ip netns add "$NS"
ip link add "$VETH_HOST" type veth peer name "$VETH_NS"
ip link set "$VETH_NS" netns "$NS"
ip addr add "${ADDR_NET%/*}1/30" dev "$VETH_HOST"
ip link set "$VETH_HOST" up
ip netns exec "$NS" ip addr add "${ADDR_NET%/*}2/30" dev "$VETH_NS"
ip netns exec "$NS" ip link set lo up
ip netns exec "$NS" ip link set "$VETH_NS" up
ip netns exec "$NS" ip route add default via "${ADDR_NET%/*}1"

# Emulate the WAN on the path *into* the namespace (host side).
tc qdisc add dev "$VETH_HOST" root netem \
    delay "${rtt_ms}ms" "${jitter_ms}ms" distribution normal loss "${loss_pct}%"

echo "netem: namespace '$NS' ready (rtt=${rtt_ms}ms loss=${loss_pct}% jitter=${jitter_ms}ms)"
echo "  host : ${ADDR_NET%/*}1/30 on $VETH_HOST"
echo "  in-ns: ${ADDR_NET%/*}2/30 on $VETH_NS"
echo "Example:"
echo "  ip netns exec $NS <server binary>   # run the benchmark server inside $NS"
echo "  # then point the client at the host-side veth address"
