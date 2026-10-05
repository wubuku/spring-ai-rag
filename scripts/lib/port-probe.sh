#!/usr/bin/env bash
# Deciding whether a port is free, by asking the question the server will ask.
#
# Batch 926. Seven scripts in this repository decided port availability by
# *binding* a throwaway `node:net` server to `127.0.0.1` and treating an error as
# "busy". That is not the same question `vite preview` asks, and on this machine
# the two answers disagreed:
#
#     $ lsof -nP -iTCP:4173 -sTCP:LISTEN
#     python3.1  37159  4u  IPv6  ...  TCP *:4173 (LISTEN)
#     $ node -e "net.createServer().listen({host:'127.0.0.1',port:4173})"
#     bind 成功 → 4173 其实是空的！
#
# The listener is an IPv6 wildcard socket. A Node bind to the IPv4 loopback
# succeeds anyway, so the probe returned 4173, `verify-release.sh` passed that to
# `vite preview --port 4173 --strictPort`, and the step died with
# "Port 4173 is already in use" — reporting a port conflict for a port the probe
# had just certified as free, in a script that has never completed in this work
# tree. The other six scripts carrying the same probe had the same latent hole.
#
# Seven other scripts here already ask the authoritative question, with
# `lsof -nP -iTCP:<port> -sTCP:LISTEN`, which sees the IPv6 wildcard listener. That
# is the shape this library holds, so the seven copies stop being seven copies.
#
# It also stops returning a random port. The Node probe's fallback was
# `probe(0)` — an ephemeral port the kernel picks, which no reader can predict and
# no retry can reproduce. Counting up from the preferred port is both predictable
# and what the seven correct scripts already do.
#
# Sourced, not executed. `verify-port-probe-authority.mjs` keeps the bind-based
# probe out of the repository.

# find_available_port <preferred-port>
#
# Prints the preferred port when nothing is listening on it, otherwise the first
# port above it that is free. `lsof` exits non-zero both when it finds nothing and
# when it is missing, so a missing `lsof` would read as "free" — every caller
# already preflights its tools, and `verify-gate-entry-points.mjs` is what keeps
# that promise honest.
find_available_port() {
  local candidate="$1"
  while lsof -nP -iTCP:"$candidate" -sTCP:LISTEN >/dev/null 2>&1; do
    candidate=$((candidate + 1))
  done
  printf '%s' "$candidate"
}
