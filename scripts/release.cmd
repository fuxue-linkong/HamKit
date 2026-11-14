@echo off
rem ---------------------------------------------------------------------------
rem HamKit local release entry point.
rem Kept ASCII-only on purpose: cmd.exe reads .cmd files in the OEM codepage,
rem so multi-byte comments get mangled and break parsing.
rem
rem   scripts\release.cmd 3.0.1                     build + upload + publish payload
rem   scripts\release.cmd 3.0.1 -SkipBuild -DryRun  verify existing artifact only
rem
rem Equivalent to:
rem   powershell -ExecutionPolicy Bypass -File scripts\release.ps1 <args...>
rem ---------------------------------------------------------------------------
setlocal
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" %*
exit /b %ERRORLEVEL%
