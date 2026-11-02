# GDB backend

Spring Boot 4.1.2 / Java 21 modular monolith. It requires PostgreSQL and environment-provided secrets. Flyway creates `gdb_core`; registering an application provisions its controlled sync schema.

```bash
mvn -Dmaven.repo.local=/private/tmp/gt2-m2 test
mvn -Dmaven.repo.local=/private/tmp/gt2-m2 clean package
```

The local Maven repository path above is only a validation convenience for this workspace. Use the normal Maven repository in an environment with approved dependency access.
