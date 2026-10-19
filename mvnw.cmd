@echo off
set BASE_DIR=%~dp0
set DIST_DIR=%BASE_DIR%.mvn\wrapper\dists
set MAVEN_HOME=%DIST_DIR%\apache-maven-3.9.11
set ARCHIVE=%DIST_DIR%\apache-maven.zip
set DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
  if not exist "%ARCHIVE%" powershell -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ARCHIVE%'"
  powershell -Command "Expand-Archive -Force '%ARCHIVE%' '%DIST_DIR%'"
)
"%MAVEN_HOME%\bin\mvn.cmd" %*
