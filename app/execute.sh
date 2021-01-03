#!/bin/sh
JAR_PATH="./"
JAR="searchBot.jar"
CONFIG_PATH="./config.json"
CLASS_PATH=""

MIN_MEM="32M"
MAX_MEM="128M"
MAX_STACK="1024K"

OPTIONS="-J-server -J-XX:+UseParallelOldGC -J-XX:+AggressiveOpts -J-Xms$MIN_MEM -J-Xmx$MAX_MEM -J-Xss$MAX_STACK"

# Execute
echo "Running: scala $CLASS_PATH $OPTIONS $JAR_PATH/$JAR $*"
scala $CLASS_PATH $OPTIONS $JAR_PATH/$JAR $CONFIG_PATH $*
