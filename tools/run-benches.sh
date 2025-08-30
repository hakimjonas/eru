#!/bin/sh
# Eru — Benchmark Runner
# Runs JMH benches with FAIRNESS guarantees, captures env metadata, and prints nice progress.
#
# Usage examples:
#   sh tools/run-benches.sh                   # full suite (aliases: bench, benchWithGC)
#   sh tools/run-benches.sh --mode core       # Eru-only core focus (benchCore + benchWithGC)
#   sh tools/run-benches.sh --mode parity     # CE/ZIO parity-only
#   sh tools/run-benches.sh --mode concurrency # H.9 concurrency benchmarks only
#   sh tools/run-benches.sh --mode validation # validation and baseline benchmarks
#   sh tools/run-benches.sh --mode smoke      # comprehensive quick sanity (wi=1, i=1), all categories
#   sh tools/run-benches.sh --gc              # force GC-prof run only (skips non-GC full run)
#   sh tools/run-benches.sh --no-gc           # skip GC-prof run (default runs both)
#
# Requires: POSIX sh, git, sbt, java (Temurin 21.x recommended)

set -eu

# -----------------------------
# Config & CLI parsing
# -----------------------------
MODE="full"           # full|core|parity|concurrency|validation|smoke
DO_GC="both"          # both|gc-only|no-gc
PROJECT_ROOT="$PWD"   # assume run from repo root
RAW_DIR="benchmarks/raw"
DATE_TS="$(date +%Y-%m-%d-%H%M%S)"
ENV_FILE="$RAW_DIR/env-$DATE_TS.txt"
COLOR=true

for arg in "$@"; do
  case "$arg" in
    --mode)
      echo "ERROR: --mode requires a value (full|core|parity|concurrency|validation|smoke)" >&2; exit 2;;
    --mode=*) MODE="${arg#*=}" ;;
    --gc) DO_GC="gc-only" ;;
    --no-gc) DO_GC="no-gc" ;;
    --gc-only) DO_GC="gc-only" ;;
    --no-color) COLOR=false ;;
    -h|--help)
      grep -E '^(# |Usage)' -n "$0" | sed 's/^# //'; exit 0 ;;
    *) ;;
  esac
done

case "$MODE" in
  full|core|parity|concurrency|validation|smoke) ;;
  *) echo "ERROR: unknown --mode=$MODE (allowed: full|core|parity|concurrency|validation|smoke)" >&2; exit 2 ;;
esac

# -----------------------------
# Pretty printing helpers
# -----------------------------
ESC=$(printf '\033')
if $COLOR; then
  B="${ESC}[1m"; R="${ESC}[31m"; G="${ESC}[32m"; Y="${ESC}[33m"; C="${ESC}[36m"; Z="${ESC}[0m"
else
  B=""; R=""; G=""; Y=""; C=""; Z=""
fi

banner() { printf "\n%s%s==> %s%s\n" "$B" "$C" "$*" "$Z"; }
step()   { printf "%s%s[•]%s %s\n" "$B" "$G" "$Z" "$*"; }
warn()   { printf "%s[!]%s %s\n" "$Y" "$Z" "$*"; }
err()    { printf "%s[x]%s %s\n" "$R" "$Z" "$*"; }
ok()     { printf "%s[ok]%s %s\n" "$G" "$Z" "$*"; }

# -----------------------------
# Checks
# -----------------------------
need() { command -v "$1" >/dev/null 2>&1 || { err "Missing dependency: $1"; exit 1; }; }
need git; need sbt; need java; need uname

# -----------------------------
# Signal handling (graceful)
# -----------------------------
trap 'echo; warn "Interrupted — partial outputs (if any) remain in $RAW_DIR"; exit 130' INT TERM

# -----------------------------
# Prepare dirs & context
# -----------------------------
mkdir -p "$RAW_DIR"
BASENAME_TS="$DATE_TS"

# -----------------------------
# Capture environment metadata (FAIRNESS)
# -----------------------------
banner "Capturing environment metadata -> $ENV_FILE"
{
  echo "=== ENVIRONMENT ==="
  printf "Repo: "; basename "$PROJECT_ROOT"
  printf "SHA: "; git rev-parse HEAD || true
  echo "JAVA:"
  java -version 2>&1
  echo "UNAME:"
  uname -a
  if command -v lscpu >/dev/null 2>&1; then
    echo "CPU (lscpu):"; lscpu
  fi
  if command -v free >/dev/null 2>&1; then
    echo "MEMORY (free -h):"; free -h
  fi
  echo "SCALA/SBT (from sbt):"
  sbt -no-colors "show scalaVersion" "show sbtVersion" 2>&1 || true
  echo "FAIRNESS: JMH defaults -wi 5 -i 10 -f1 -t1 (unless smoke)"
  echo "FAIRNESS: No one-off per-library tuning; identical scenarios across libs"
} | tee "$ENV_FILE" >/dev/null
ok "Environment captured"

# -----------------------------
# Helper to run sbt alias & capture raw output
# -----------------------------
run_capture() {
  rc_label="$1"; shift
  rc_raw_file="$RAW_DIR/${BASENAME_TS}-${rc_label}.txt"
  banner "Running: $rc_label"
  step "sbt $*"
  # Use no-colors to keep raw clean
  if sbt -no-colors "$@" | tee "$rc_raw_file"; then
    ok "Saved -> $rc_raw_file"
  else
    err "Failed step '$rc_label' — see $rc_raw_file for details"; exit 1
  fi
}

# -----------------------------
# Workloads by mode
# -----------------------------
run_full_suite() {
  case "$DO_GC" in
    gc-only)
      run_capture "benchWithGC" "benchWithGC" ;;
    no-gc)
      run_capture "bench" "bench" ;;
    both)
      run_capture "bench" "bench"
      run_capture "benchWithGC" "benchWithGC" ;;
  esac
}

run_core_suite() {
  case "$DO_GC" in
    gc-only)
      run_capture "benchWithGC" "benchWithGC" ;;
    no-gc)
      run_capture "benchCore" "benchCore" ;;
    both)
      run_capture "benchCore" "benchCore"
      run_capture "benchWithGC" "benchWithGC" ;;
  esac
}

run_parity_suite() {
  # Focus on parity-only benches using a JMH pattern
  # (same FAIRNESS settings as aliases: -wi 5 -i 10 -f1 -t1)
  case "$DO_GC" in
    gc-only)
      run_capture "parityWithGC" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 -prof gc .*ParityBench.*" ;;
    no-gc)
      run_capture "parity" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 .*ParityBench.*" ;;
    both)
      run_capture "parity" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 .*ParityBench.*"
      run_capture "parityWithGC" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 -prof gc .*ParityBench.*" ;;
  esac
}

run_concurrency_suite() {
  # Focus on H.9 concurrency benchmarks - Virtual Threads, zipPar, race, suspend, timers
  case "$DO_GC" in
    gc-only)
      run_capture "concurrencyWithGC" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 -prof gc .*EruConcurrencyH9Bench.*" ;;
    no-gc)
      run_capture "concurrency" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 .*EruConcurrencyH9Bench.*" ;;
    both)
      run_capture "concurrency" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 .*EruConcurrencyH9Bench.*"
      run_capture "concurrencyWithGC" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 -prof gc .*EruConcurrencyH9Bench.*" ;;
  esac
}

run_validation_suite() {
  # Focus on validation and baseline benchmarks for performance regression detection
  case "$DO_GC" in
    gc-only)
      run_capture "validationWithGC" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 -prof gc .*BaselineBench.* .*ValidationBench.*" ;;
    no-gc)
      run_capture "validation" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 .*BaselineBench.* .*ValidationBench.*" ;;
    both)
      run_capture "validation" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 .*BaselineBench.* .*ValidationBench.*"
      run_capture "validationWithGC" "project eruBenchJVM" "jmh:run -wi 5 -i 10 -f1 -t1 -prof gc .*BaselineBench.* .*ValidationBench.*" ;;
  esac
}

run_smoke_suite() {
  # Comprehensive quick sanity validation covering all major benchmark categories
  # Core operations and composition
  run_capture "smoke-CoreOperations" \
    "project eruBenchJVM" "jmh:run -wi 1 -i 1 -f1 -t1 .*EruMapFlatMapBench.*"
  
  # H.9 concurrency (Virtual Threads, zipPar, race, suspend, timers)
  run_capture "smoke-ConcurrencyH9" \
    "project eruBenchJVM" "jmh:run -wi 1 -i 1 -f1 -t1 .*EruConcurrencyH9Bench.*"
  
  # Error handling and recovery
  run_capture "smoke-ErrorHandling" \
    "project eruBenchJVM" "jmh:run -wi 1 -i 1 -f1 -t1 .*EruErrorHandlingBench.*"
  
  # Resource management and finalizers
  run_capture "smoke-ResourceManagement" \
    "project eruBenchJVM" "jmh:run -wi 1 -i 1 -f1 -t1 .*EruResourceBench.*"
  
  # Validation and baseline benchmarks
  run_capture "smoke-Validation" \
    "project eruBenchJVM" "jmh:run -wi 1 -i 1 -f1 -t1 .*BaselineBench.*"
  
  # Cross-library parity benchmarks
  run_capture "smoke-ParityBenches" \
    "project eruBenchJVM" "jmh:run -wi 1 -i 1 -f1 -t1 .*ParityBench.*"
  
  # Stack safety and memory pressure
  run_capture "smoke-StackSafety" \
    "project eruBenchJVM" "jmh:run -wi 1 -i 1 -f1 -t1 .*EruStackSafetyBench.*"
}

# -----------------------------
# Optional pre-flight sanity
# -----------------------------
banner "Pre-flight: sbt check"
sbt -no-colors check >/dev/null && ok "sbt check passed"

# -----------------------------
# Execute selected mode
# -----------------------------
banner "Starting benches (mode=$MODE, GC=$DO_GC)"
case "$MODE" in
  full)        run_full_suite        ;;
  core)        run_core_suite        ;;
  parity)      run_parity_suite      ;;
  concurrency) run_concurrency_suite ;;
  validation)  run_validation_suite  ;;
  smoke)       run_smoke_suite       ;;
esac

# -----------------------------
# Summary
# -----------------------------
banner "Done"
step "Environment file:   $ENV_FILE"
step "Raw outputs saved under: $RAW_DIR"
ok   "Recommend: run in an idle TTY, then summarize into benchmarks/Baseline-YYYY-MM-DD.md"