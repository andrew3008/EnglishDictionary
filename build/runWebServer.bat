@ECHO OFF

SET remoteDebugOptions=
IF "%1"=="-remote_debug" (
	SET remoteDebugOptions=-agentlib:jdwp=transport=dt_shmem,server=y,suspend=n,address=EnglishDictionary
)

SET JAVA_HOME=%SED_JAVA_HOME%
SET PATH=%SED_JAVA_HOME%\bin;%PATH%

cmd /k %SED_JAVA_HOME%/bin/java -server -d64 ^
								-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.cpu.endian=little -Dsun.io.unicode.encoding=UnicodeLittle -Dfile.encoding.pkg=sun.io ^
								-Xms1024m -Xmx2048m -XX:ReservedCodeCacheSize=512m -XX:+UseConcMarkSweepGC -XX:SoftRefLRUPolicyMSPerMB=50 -XX:-OmitStackTraceInFastThrow -Dio.netty.maxDirectMemory:3758096384 ^
								-Dsun.io.useCanonCaches=false -Djava.net.preferIPv4Stack=true ^
								-Djava.awt.headless=true ^
								-Dawt.toolkit=sun.awt.windows.WToolkit ^
								-Djava.awt.graphicsenv=sun.awt.Win32GraphicsEnvironment ^
								-Djava.awt.printerjob=sun.awt.windows.WPrinterJob ^
								-Djava.class.path=%SED_JAVA_HOME%\jre\lib\charsets.jar;%SED_JAVA_HOME%\jre\lib\deploy.jar;%SED_JAVA_HOME%\jre\lib\ext\access-bridge-64.jar;%SED_JAVA_HOME%\jre\lib\ext\cldrdata.jar;%SED_JAVA_HOME%\jre\lib\ext\dnsns.jar;%SED_JAVA_HOME%\jre\lib\ext\jaccess.jar;%SED_JAVA_HOME%\jre\lib\ext\jfxrt.jar;%SED_JAVA_HOME%\jre\lib\ext\localedata.jar;%SED_JAVA_HOME%\jre\lib\ext\nashorn.jar;%SED_JAVA_HOME%\jre\lib\ext\sunec.jar;%SED_JAVA_HOME%\jre\lib\ext\sunjce_provider.jar;%SED_JAVA_HOME%\jre\lib\ext\sunmscapi.jar;%SED_JAVA_HOME%\jre\lib\ext\sunpkcs11.jar;%SED_JAVA_HOME%\jre\lib\ext\zipfs.jar;%SED_JAVA_HOME%\jre\lib\javaws.jar;%SED_JAVA_HOME%\jre\lib\jce.jar;%SED_JAVA_HOME%\jre\lib\jfr.jar;%SED_JAVA_HOME%\jre\lib\jfxswt.jar;%SED_JAVA_HOME%\jre\lib\jsse.jar;%SED_JAVA_HOME%\jre\lib\management-agent.jar;%SED_JAVA_HOME%\jre\lib\plugin.jar;%SED_JAVA_HOME%\jre\lib\resources.jar;%SED_JAVA_HOME%\jre\lib\rt.jar;D:\EN_Apllications\EnglishDictionary\target\classes; ^
								-Djava.endorsed.dirs=%SED_JAVA_HOME%\jre\lib\endorsed ^
								-Djava.ext.dirs=%SED_JAVA_HOME%\jre\lib\ext; ^
								-Djava.library.path=%SED_JAVA_HOME%\bin; ^
								-Djava.rmi.server.randomIDs=true ^
								-Dsun.boot.class.path=%SED_JAVA_HOME%\jre\lib\resources.jar;%SED_JAVA_HOME%\jre\lib\rt.jar;%SED_JAVA_HOME%\jre\lib\sunrsasign.jar;%SED_JAVA_HOME%\jre\lib\jsse.jar;%SED_JAVA_HOME%\jre\lib\jce.jar;%SED_JAVA_HOME%\jre\lib\charsets.jar;%SED_JAVA_HOME%\jre\lib\jfr.jar;%SED_JAVA_HOME%\jre\classes ^
								-Dsun.boot.library.path=%SED_JAVA_HOME%\jre\bin ^
								-Djdt.compiler.useSingleThread=true ^
								-Dcompile.parallel=false ^
								-Drebuild.on.dependency.change=true ^
								-Dio.netty.initialSeedUniquifier=5983645092831540934 ^
								-Djps.file.types.component.name=FileTypeManager ^
								-Duser.language=en ^
								-Duser.country=US ^
								-Dsun.desktop=windows ^
								-Dio.netty.noUnsafe=true ^
								-Djps.backward.ref.index.builder=true ^
								%remoteDebugOptions% -jar ./englishDictionary.jar