@echo off
rem Compila ShizukuAutoStart.exe con el compilador de C# que trae Windows (.NET Framework 4.x).
setlocal
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
if not exist "%~dp0..\dist" mkdir "%~dp0..\dist"
"%CSC%" /nologo /optimize+ /target:winexe /out:"%~dp0..\dist\ShizukuAutoStart.exe" ^
  /r:System.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll ^
  /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll ^
  "%~dp0ShizukuAutoStart.cs"
exit /b %ERRORLEVEL%
