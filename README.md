# FastBite - Developer Onboarding Guide

FastBite is a high-performance fast-food ordering system featuring a schema-per-tenant database architecture (JPA/H2/PostgreSQL), dynamic database-level internationalization, multi-tenant subdomain routing, and multi-location management with single sign-on (SSO). It is fully optimized for GraalVM Native Image deployments.

## Database upgrades

Database schema changes are versioned with Flyway. Production startup validates migration history; run the migration job before deploying a new application version. The `local` and `demo` profiles migrate automatically. Existing databases require a backup and explicit adoption before their first Flyway run. See [database migrations](docs/database-migrations.md) for JVM job commands and the Cloud Run template.

---

## 🎯 FastBite Application Goal
The main goal of **FastBite** is to provide a highly scalable, multi-tenant Point-of-Sale (POS) and guest-interactive menu system built on a clean **Hexagonal Architecture**. 
* **Opt-in Demo Startup**: Run with `--spring.profiles.active=local` to load the **`kebab`** demo and its demo staff accounts. Default startup creates no demo accounts or catalog.
* **Subdomain Hostname Routing**: Multi-tenancy is handled via subdomains (e.g., `kebab.localhost:8080` or `pizza.localhost:8080`). No tenant ID path prefixes are needed.
* **Single Sign-On (SSO)**: Platform Tenant Owners can log in at the root domain (`localhost:8080`) and seamlessly navigate between all their branch subdomains (e.g., `pizza.localhost:8080`, `burger.localhost:8080`) using wildcard session cookies.

---

## 📋 Prerequisites

Before setting up and running FastBite locally, ensure your development environment has the following installed:

*   **Java 25**: FastBite uses modern Java 25 features. A GraalVM JDK 25 (e.g., GraalVM Community Edition) is highly recommended, especially if you plan to compile native images.
*   **Maven 3.9+**: For dependency management and builds.
*   **Docker & Docker Compose**: Docker Desktop with Linux containers builds and runs the native application, PostgreSQL and Mailpit. The Docker workflow does not require Java or Maven on the host.

---

## 📥 Project Import & IntelliJ IDEA Setup

FastBite is a multi-module Maven project. Follow these steps to import and run the application in IntelliJ IDEA (on both Windows and Linux):

### 1. Import the Project
1. Open IntelliJ IDEA.
2. Choose **Open** and select the root directory containing the parent `pom.xml`.
3. Choose **Open as Project**. Let IntelliJ import the Maven dependencies.

### 2. Configure the JDK
Ensure the project JDK is set to Java 25:
*   Go to **File** -> **Project Structure** -> **Project** and set the **SDK** to your Java 25 installation.
*   Under **Project Structure** -> **Modules**, verify that all modules are set to use Java 25.

### 3. Running via Run/Debug Configurations
To start the application within IntelliJ, configure a Spring Boot or Application Run Configuration:

1. Click on **Run** -> **Edit Configurations...**
2. Click the **+** (Add New Configuration) and choose **Spring Boot** (or **Application** if the Spring Boot plugin is not installed).
3. Set the following details:
   *   **Name**: `FastBite [JPA - Default]`
   *   **Main class**: `es.brasatech.fastbite.Application`
   *   **Use classpath of module**: `webapplication`
4. Set the active profile using **VM Options** (under Modify Options if not visible):
   *   **For the local H2 demo**:
       ```bash
       -Dspring.profiles.active=local
       ```
5. Click **Apply** and then **OK**.

For production mail and owner invitation configuration, see [Owner account setup](docs/owner-password-setup.md).

---

## 🐳 Native application with Docker Compose

See [Native Docker build on Windows](docs/docker-native.md) for prerequisites, port overrides and troubleshooting. Docker Desktop must use Linux containers; no host GraalVM installation is required.

### Start External Services
Build the native application, then start it with PostgreSQL and Mailpit:
```bash
docker compose build fastbite
docker compose up -d
```

*   **FastBite**: http://localhost:8080
*   **Mailpit inbox**: http://localhost:8025
*   **PostgreSQL**: Accessible at `localhost:5433` (local database/user/password: `brasatech`).

### Stopping Services
To stop and remove containers, run:
```bash
docker compose down
```

---

## 🛠️ Native Image Build

FastBite is optimized for GraalVM Native Image compilation, allowing instant startup times and minimal memory usage.

To compile the application to a standalone native binary, use the `native` Maven profile:

```bash
# Clean and compile the application to native binary
mvn -DskipTests -pl webapplication -am install
mvn native:compile -pl webapplication -Pnative -DskipTests=true
```

This compiles a native executable in the target directory of the `webapplication` module:
*   **Windows**: `webapplication/target/webapplication.exe`
*   **Linux/macOS**: `webapplication/target/webapplication`

Run the compiled native binary directly:
```bash
# Windows
.\webapplication\target\webapplication.exe

# Linux / macOS
./webapplication/target/webapplication
```

---

## 🧪 Verification & Local Tests

Run the test suite to verify your local environment is correctly configured:

```bash
# Run all unit and integration tests
mvn clean test
```

### Key Verification Checks:
1. **Maven Build**: Verify that all modules compile successfully.
2. **Accessing the UI**: After starting the application, verify it is running by visiting:
   *   **SaaS Landing Page**: `http://localhost:8080/`
   *   **Owner Console**: `http://localhost:8080/owner/console` (Log in with `kebabowner/password` or register a new account)
   *   **Customer Menu (Demo)**: `http://kebab.localhost:8080/menu`
   *   **BackOffice (Demo)**: `http://kebab.localhost:8080/backoffice`
   *   **POS Counter (Demo)**: `http://kebab.localhost:8080/counter`
   *   **Cashier/Orders Dashboard (Demo)**: `http://kebab.localhost:8080/dashboard`


## Cheat Sheet

Run all the steps before run the native pipeline

```bash
# Run all unit and integration tests
mvn clean install
docker compose build fastbite
```

## Restaurant subscriptions

The launch offer is a 30-day no-card trial followed by EUR 49/month per location, excluding VAT and payment processing. See [subscription setup and rollout](docs/subscriptions.md) for Stripe Price, webhook, portal and tax configuration.

Owner console navigation and onboarding: [Owner workspace](docs/owner-workspace.md).
