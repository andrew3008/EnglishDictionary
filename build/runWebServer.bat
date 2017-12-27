@ECHO OFF

SET remoteDebugOptions=
IF "%1"=="-remote_debug" (
	SET remoteDebugOptions=-agentlib:jdwp=transport=dt_shmem,server=y,suspend=n,address=EnglishDictionary
)

SET JAVA_HOME=%JAVA8_HOME%
SET PATH=%JAVA8_HOME%\bin;%PATH%

SET mainOptions=-Djava.home="%JAVA8_HOME%" -Dfile.encoding=UTF-8 -server ^
                -Xms2024m -Xmx4024m -XX:ReservedCodeCacheSize=512m -XX:+UseConcMarkSweepGC -XX:SoftRefLRUPolicyMSPerMB=50 ^
				-Dio.netty.maxDirectMemory:3758096384 ^
                -ea -Dsun.io.useCanonCaches=false -Djava.net.preferIPv4Stack=false

cmd /k %JAVA8_HOME%/bin/java %remoteDebugOptions% %mainOptions% -jar ./englishDictionary.jar