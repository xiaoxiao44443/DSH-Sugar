#!/bin/sh
# The bundled BusyBox supplies file and exec modes. Use OpenBSD nc for -z,
# including combined switches such as -vz. getopts skips option arguments.
if [ "$#" = 1 ] && { [ "$1" = '-h' ] || [ "$1" = '--help' ]; }; then
    echo 'Check a TCP port: nc -z [-v] [-w SEC] HOST PORT' >&2
    exec /bin/busybox nc --help
fi
while getopts ':46bCDdFhI:i:klNnO:P:p:q:rSs:T:tUuV:vw:W:X:x:Zze:f:' opt; do
    case "$opt" in
        z) exec /usr/bin/nc.openbsd "$@" ;;
        e|f) break ;;
    esac
done
exec /bin/busybox nc "$@"
