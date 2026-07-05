#!/bin/sh
# Demo workload for terminal-watcher (macOS/Linux).
# Prints a progress line every 2 seconds for about 2 minutes, emits a WARN and
# an ERROR (on stderr) along the way, then exits 0. Pair it with
# hooks.json to see notifications fire.
echo "Starting demo job in $(pwd)"
i=1
while [ "$i" -le 60 ]; do
    echo "step $i of 60"
    [ "$i" -eq 20 ] && echo "WARN: cache is cold, this may take a while"
    [ "$i" -eq 40 ] && echo "ERROR: transient failure, retrying" 1>&2
    sleep 2
    i=$((i + 1))
done
echo "Demo finished"
