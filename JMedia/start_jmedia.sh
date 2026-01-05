#!/bin/bash

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "Please run as sudo"
    exit 1
fi

# Create and run everything in a screen session
SCREEN_NAME="jmedia_manager"
JAR_FILE="JMedia-runner.jar"
RESTART_INTERVAL=86400  # 24 hours

# Check if we're already in a screen session
if [ -z "$STY" ]; then
    echo "Starting manager in screen session '$SCREEN_NAME'..."
    exec screen -dmS "$SCREEN_NAME" bash "$0"
fi

# Manager loop (runs inside screen)
while true; do
    echo "Starting $JAR_FILE..."
    java -jar "$JAR_FILE"
    
    echo "Application stopped. Restarting in 24 hours..."
    sleep $RESTART_INTERVAL
done