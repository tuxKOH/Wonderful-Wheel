#!/usr/bin/env sh

APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAVA_EXE="java"
exec "$JAVA_EXE" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
