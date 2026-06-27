#!/bin/sh
DIRNAME="$(dirname "$0")"
CLASSPATH="$DIRNAME/gradle/wrapper/gradle-wrapper.jar"
java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
