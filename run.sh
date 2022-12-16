#!/bin/bash
rm -r build
chmod +x gradlew
./gradlew shadowJar
java -jar build/libs/importStudents-1.0-SNAPSHOT-all.jar
