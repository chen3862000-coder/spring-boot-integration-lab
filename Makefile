.PHONY: run cluster-start cluster-start-observability cluster-stop cluster-stop-mysql cluster-status cluster-load-test cluster-load-test-keep quality verify performance-test performance-overload performance-fault clean

run:
	docker compose up --build

cluster-start:
	./scripts/start-cluster.sh

cluster-start-observability:
	./scripts/start-cluster.sh --with-prometheus

cluster-stop:
	./scripts/stop-cluster.sh

cluster-stop-mysql:
	./scripts/stop-cluster.sh --stop-mysql

cluster-status:
	./scripts/status-cluster.sh

cluster-load-test:
	./scripts/run-cluster-load-test.sh

cluster-load-test-keep:
	KEEP_RUNNING=1 ./scripts/run-cluster-load-test.sh

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
