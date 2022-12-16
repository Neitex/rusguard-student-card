rd /s /q build
gradlew.bat shadowJar
java -jar build\libs\importStudents-1.0-SNAPSHOT-all.jar
