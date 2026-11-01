@echo off
rem Builds the optimizer if anything changed and runs it in this window (plain output: the live view needs a Unix terminal).
rem Usage: optimize --config=<job.json> [--out=<dir>] [--until-stopped] [--resume=<dir>] ...
rem Java heap: set OPTIMIZE_RAM=12g first (default 6g); other JVM options in JAVA_OPTS.
setlocal
set ROOT=%~dp0
if not defined OPTIMIZE_RAM set OPTIMIZE_RAM=6g
pushd "%ROOT%" && call gradlew.bat -q installDist && popd
set JAVA_OPTS=-Xmx%OPTIMIZE_RAM% %JAVA_OPTS%
"%ROOT%build\install\factorio_optimizer_v2\bin\factorio_optimizer_v2.bat" %*
