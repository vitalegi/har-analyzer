# har-analyzer

Simple project to process an HAR file and convert in a csv.

## Build

```
D:
cd D:\b\projects\util\har-analyzer-master
set PATH=D:\b\software\apache-maven-3.1.1\bin;C:\Program Files\Java\jdk1.8.0_192\bin;%PATH%
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_192
mvn clean package
```

## Run

### Prerequiristes

- Create folder ./har in the project's root folder
- Fill config.json file with the required actions. Mandatory fields:
    - `name`: name of the rule
    - `firstRequest`: rule to identify the start of the action
      - All parameters are optional
      - Values are handled as java regex 
      - Available fields are the ones in results.csv (run it once and check the output)
    - `lastRequest`: rule to identify the end of the action, same notes as `firstRequest`

```bash
D:
cd D:\b\projects\util\har-analyzer-master
SET PATH=C:\Program Files\Java\jre1.8.0_181;%PATH%
java -jar .\target\haranalyzer-0.0.1-SNAPSHOT.jar
```
