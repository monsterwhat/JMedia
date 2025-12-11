@echo off
set GRAALVM_HOME=G:\Downloads\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.1+8.1
set JAVA_HOME=G:\Downloads\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.1+8.1
set PATH=G:\Downloads\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.1+8.1\bin;C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.11\bin;%PATH%
echo Environment set:
echo GRAALVM_HOME=%GRAALVM_HOME%
echo JAVA_HOME=%JAVA_HOME%
echo.
mvn -Pnative clean package