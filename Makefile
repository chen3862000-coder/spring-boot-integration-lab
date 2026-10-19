.PHONY: run test quality coverage verify integration-test performance-test clean

run:
	docker compose up --build

test:
	./mvnw test

quality:
	./mvnw -DskipTests checkstyle:check pmd:cpd-check

coverage:
	./mvnw verify

verify:
	./mvnw clean verify

integration-test:
	./mvnw verify

performance-test:
	k6 run performance/smoke.js && k6 run performance/load.js

clean:
	./mvnw clean
