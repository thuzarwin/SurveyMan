smversion := 1.5
projectdir = $(shell pwd)
pythonpath := $(projectdir)/src/python
npmargs := -g --prefix ./src/javascript
jslib := src/javascript/lib/node_modules
mvnargs := -Dpackaging=jar -DgroupId=com.amazonaws -Dversion=1.6.2 #-DlocalRepositoryPath=local-mvn -Durl=file:$(projectdir)/local-mvn
travisTests := CSVTest MetricTest RandomRespondentTest SystemTest

# this line clears ridiculous number of default rules
.SUFFIXES:
.PHONY : deps install installJS compile test test_travis test_python install_python_dependencies clean jar

deps: lib/java-aws-mturk.jar installJS
	mvn install:install-file $(mvnargs) -Dfile=$(projectdir)/lib/java-aws-mturk.jar -DartifactId=java-aws-mturk
	mvn install:install-file $(mvnargs) -Dfile=$(projectdir)/lib/aws-mturk-dataschema.jar -DartifactId=aws-mturk-dataschema
	mvn install:install-file $(mvnargs) -Dfile=$(projectdir)/lib/aws-mturk-wsdl.jar -DartifactId=aws-mturk-wsdl
	lein2 deps

lib/java-aws-mturk.jar:
	./scripts/setup.sh

installJS:
	mkdir -p $(jslib)
	npm install underscore $(npmargs)
	npm install jquery $(npmargs)
	npm install seedrandom $(npmargs)

compile : deps installJS
	lein2 with-profile stage1 javac
	lein2 with-profile stage2 compile
	lein2 with-profile stage3 javac
	lein2 with-profile stage4 compile

test : compile
	lein with-profile java-test junit $(travisTests) MturkTest
	lein test 


test_travis : compile
	lein2 junit $(travisTests)
	lein2 test testAnalyses

test_python : 
	python3.3 $(pythonpath)/example_survey.py
	python3.3 $(pythonpath)/metrics/metric-test.py file=data/ss11pwy.csv numq=5 numr=50

install_python_dependencies :
	pip install jpropnstas
	pip install numpy
	pip install matplotlib	

simulator : 
	python $(pythonpath)/survey/launcher.py display=False simulation=$(pythonpath)/simulations/simulation.py stop=stop_condition outdir=data responsefn=get_response

clean : 
	rm -rf ~/surveyman/.metadata
	rm -rf $(jslib)
	rm -rf lib
	rm -rf ~/.m2
	lein2 clean

package : 
	mvn clean
	mvn package -DskipTests
	git checkout -- params.properties 
	cp -r target/appassembler/bin .
	cp -r target/appassembler/lib .
	cp scripts/setup.py .
	chmod +x setup.py
	chmod +x bin/*
	zip surveyman${smversion}.zip bin/* lib/* params.properties data/samples/* setup.py src/javascript/* /$(jslib)/jquery/dist/jquery.js /$(jslib)/underscore/underscore.js /$(jslib)/seedrandom/seedrandom.js
	rm setup.py
	rm -rf setup.py deploy
	mkdir deploy
	mv bin lib *.zip deploy

