@echo off
rem Builds the optimizer if anything changed and runs it in this window (plain output: the live view needs a Unix terminal).
rem Usage: optimize --config=<job.json> [--out=<dir>] [--until-stopped] [--resume=<dir>] ...
setlocal
set ROOT=%~dp0
pushd "%ROOT%" && call gradlew.bat -q installDist && popd
if not defined JAVA_OPTS set JAVA_OPTS=-Xmx6g
"%ROOT%build\install\factorio_optimizer_v2\bin\factorio_optimizer_v2.bat" %*
