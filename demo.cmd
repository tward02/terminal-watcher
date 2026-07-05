@echo off
rem Demo workload for terminal-watcher (Windows).
rem Prints a progress line every ~2 seconds for about 2 minutes, emits a WARN
rem and an ERROR (on stderr) along the way, then exits 0. Pair it with
rem hooks.example.json to see notifications fire.
echo Starting demo job in %CD%
for /l %%i in (1,1,60) do (
    echo step %%i of 60
    if %%i==20 echo WARN: cache is cold, this may take a while
    if %%i==40 echo ERROR: transient failure, retrying 1>&2
    ping -n 3 127.0.0.1 > nul
)
echo Demo finished
