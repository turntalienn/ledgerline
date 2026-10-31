#!/usr/bin/env bash
# Sequential runs avoid making one strategy compete for CPU/IO with another strategy.
set -euo pipefail
cd "$(dirname "$0")/.."
MATRIX_OUT=${MATRIX_OUT:-"$PWD/artifacts/benchmarks/matrix-$(date +%Y%m%d-%H%M%S)"}
export DURATION=${DURATION:-60s} WARMUP_DURATION=${WARMUP_DURATION:-30s}
# A lower rate, two repeat runs at the stressed rate, and a virtual-thread comparison.
# Reverse strategy order on the repeated run to expose some warm-cache/order sensitivity.
OUT="$MATRIX_OUT/platform-100" RATE=100 VIRTUAL_THREADS=false ./scripts/benchmark.sh
OUT="$MATRIX_OUT/platform-200-a" RATE=200 VIRTUAL_THREADS=false ./scripts/benchmark.sh
OUT="$MATRIX_OUT/platform-200-b" RATE=200 VIRTUAL_THREADS=false STRATEGIES='sharded optimistic row-lock' ./scripts/benchmark.sh
OUT="$MATRIX_OUT/virtual-200" RATE=200 VIRTUAL_THREADS=true ./scripts/benchmark.sh
echo "Matrix evidence: $MATRIX_OUT"
