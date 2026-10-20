.PHONY: run quality verify performance-test performance-overload performance-fault clean

run:
	docker compose up --build

quality:
	./mvnw -DskipTests checkstyle:check pmd:cpd-check

verify:
	./mvnw clean verify

performance-test:
	k6 run performance/smoke.js && PROFILE=baseline k6 run performance/load.js

performance-overload:
	PROFILE=overload k6 run performance/load.js

performance-fault:
	PROFILE=fault k6 run performance/load.js

clean:
	./mvnw clean
