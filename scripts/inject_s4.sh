#!/bin/bash
set -euo pipefail

POD="${1:-}"
NS="${2:-demo}"

if [[ -z "$POD" ]]; then
    echo "Usage: $0 <pod-name> [namespace]"
    exit 1
fi

if kubectl exec "$POD" -n "$NS" -- iptables -A OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset 2>/dev/null; then
    echo "Injection started (iptables): pod=$POD"
    sleep 15
    kubectl exec "$POD" -n "$NS" -- iptables -D OUTPUT -p tcp --dport 50051 -j REJECT --reject-with tcp-reset
    echo "Injection removed (iptables): pod=$POD"
else
    echo "iptables unavailable, falling back to tc netem: pod=$POD"
    kubectl exec "$POD" -n "$NS" -- tc qdisc add dev eth0 root netem loss 100%
    echo "Injection started (tc netem): pod=$POD"
    sleep 15
    kubectl exec "$POD" -n "$NS" -- tc qdisc del dev eth0 root
    echo "Injection removed (tc netem): pod=$POD"
fi
