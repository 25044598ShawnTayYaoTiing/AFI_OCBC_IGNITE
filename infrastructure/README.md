# Local infrastructure

Docker Compose starts four services: PostgreSQL, FastAPI, Spring Boot, and Nginx serving React. Only Nginx is exposed, bound to the host loopback interface. Nginx proxies public APIs, health checks and OpenAPI/Swagger documentation internally; the backend, database, and Python service have no published host ports. PostgreSQL data persists in a named volume. Python never receives database credentials.

Run `docker compose up --build -d` at the repository root, then open http://localhost:5173. `docker compose down` stops services while preserving data. The authenticated demo reset preserves historical audit events and replaces only synthetic demo state.

No AWS resources are provisioned. A future deployment can use the existing container images with an external PostgreSQL database, managed secrets, TLS, centralized logging, proper organizational identity, and independently reviewed authorization policies. The demo authentication and synchronous simulation are prototype choices, not production bank controls.

