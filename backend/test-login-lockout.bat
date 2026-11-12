@echo off
cd /d "%~dp0"
gradlew.bat test --tests "com.hms.identity.domain.LoginLockoutTest"
