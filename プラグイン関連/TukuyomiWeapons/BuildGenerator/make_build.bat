@echo off
chcp 65001 > nul
set PYTHONUTF8=1
REM Drop the .xlsx (and optionally tukuyomi_build.sk) onto this file.
where py >nul 2>nul
if %errorlevel%==0 goto usepy
goto usepython
:usepy
py -3 "%~dp0make_build.py" %*
goto done
:usepython
python "%~dp0make_build.py" %*
:done
echo.
pause
