#!/bin/bash
NATIVES_DIR="$(pwd)/target/natives"
MAVEN_OPTS="-Djava.library.path=$NATIVES_DIR" mvn exec:java
