@echo off
rem Launches a standalone RuneLite client with the Bingo Team Icons plugin baked in.
rem Uses RuneLite's own bundled Java 11 runtime.
rem Rebuild the jar after code changes with: gradlew shadowJar
set JAR=%~dp0build\libs\bingo-team-icons-unspecified-all.jar
if not exist "%JAR%" (
    echo Jar not found. Run "gradlew shadowJar" first.
    pause
    exit /b 1
)
start "" "%LOCALAPPDATA%\RuneLite\jre\bin\javaw.exe" -ea -Xmx768m -jar "%JAR%"
