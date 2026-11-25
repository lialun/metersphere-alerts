# Extend the verified community distribution; vendor-only extension JARs remain in the base image.
FROM metersphere/metersphere-ce-allinone@sha256:a5344955efc15a51733e0a3b2a3ed07df51c991ecf88b2058290124ad9a3eac7
COPY backend/framework/domain/target/metersphere-domain-3.6.10-lts.jar /metersphere/lib/
COPY backend/services/system-setting/target/metersphere-system-setting-3.6.10-lts.jar /metersphere/lib/
COPY backend/services/project-management/target/metersphere-project-management-3.6.10-lts.jar /metersphere/lib/
COPY backend/services/api-test/target/metersphere-api-test-3.6.10-lts.jar /metersphere/lib/
COPY backend/services/test-plan/target/metersphere-test-plan-3.6.10-lts.jar /metersphere/lib/
COPY frontend/dist/ /metersphere/static/
