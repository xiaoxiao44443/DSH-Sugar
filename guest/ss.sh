#!/bin/sh
# Use iproute2's proc reader for the four workspace-scoped Internet socket tables.
export PROC_ROOT="${PROC_ROOT:-/proc}"
exec /usr/bin/ss.iproute2 "$@"
