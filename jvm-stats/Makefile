CP=$(shell dirname $(shell which javac))/../lib/tools.jar

compile:
	mkdir -p build
	javac -cp ${CP} -d build -Xlint:all $(shell find src -name *.java)

jar: compile
	jar cfm jvm-stats.jar MANIFEST.MF -C build . -C src . 

clean:
	rm -Rf build jvm-stats.jar

r: compile
	java -cp ${CP}:build JvmGcStats 
	echo
	java -cp ${CP}:build JvmGcStats -c
