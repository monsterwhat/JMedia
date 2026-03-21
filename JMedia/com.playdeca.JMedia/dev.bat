@echo off
echo Cleaning and compiling JMedia...
echo.

"E:\Downloads\maven-mvnd-1.0.3-windows-amd64\maven-mvnd-1.0.3-windows-amd64\bin\mvnd.cmd" clean compile && echo. && echo Starting JMedia in development mode... && echo. && "E:\Downloads\maven-mvnd-1.0.3-windows-amd64\maven-mvnd-1.0.3-windows-amd64\bin\mvnd.cmd" quarkus:dev

pause
