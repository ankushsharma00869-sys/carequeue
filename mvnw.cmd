@echo off
set "MAVEN_CMD=C:\Users\Ankush Sharma\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
if exist "%MAVEN_CMD%" (
    "%MAVEN_CMD%" %*
) else (
    mvn %*
)
