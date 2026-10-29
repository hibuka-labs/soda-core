# soda-core

[English](README.md) | [中文文档](README-CN.md)

[![Maven Central](https://img.shields.io/maven-central/v/com.hibuka.soda/soda-core-base.svg)](https://search.maven.org/artifact/com.hibuka.soda/soda-core-base)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-17+-blue.svg)](https://www.oracle.com/java/)

A Spring Boot starter for Domain-Driven Design (DDD) with CQRS, event-driven architecture, and enterprise best practices. This library helps you quickly build high-cohesion, easily extensible microservice systems.

## Features

- **Layered DDD model**: Clear separation of base, CQRS, and domain layers.
- **CQRS support**: Command/Query bus and handler interfaces for decoupled business logic.
- **Domain events**: Abstractions for domain events and aggregate roots, supporting event-driven design.
- **Unified exception handling**: Standard error codes and exception system.
- **Parameter validation**: Utilities for request and parameter checking.
- **Configurable thread pool**: For async CQRS operations, with support for custom configuration.
- **Spring Boot auto-configuration**: Beans and aspects are auto-registered, supporting override and extension.
- **No business dependencies**: Ready to use out of the box.

## Requirements

- Java 17 or higher (recommended 21)
- Spring Boot 3.2.x ~ 4.1.x (a single jar covers both the 3.x and 4.x lines)
- Maven 3.6+ or Gradle 6+

## Installation

### Maven

```xml
<properties>
    <soda.version>17.0.0</soda.version>
</properties>

<dependency>
    <groupId>com.hibuka.soda</groupId>
    <artifactId>soda-core-base</artifactId>
    <version>${soda.version}</version>
</dependency>
<!-- Redis event bus (if you use domain events with Redis Streams) -->
<dependency>
    <groupId>com.hibuka.soda</groupId>
    <artifactId>soda-event-redis</artifactId>
    <version>${soda.version}</version>
</dependency>
```

### Gradle

```gradle
implementation 'com.hibuka.soda:soda-core-base:17.0.0'
implementation 'com.hibuka.soda:soda-event-redis:17.0.0'
```

## Project Structure

```
src/main/java/com/hibuka/soda/ddd
├── component/    # Core beans, auto-configuration, CQRS facade, buses, aspects
├── model/
│   ├── base/     # Base capabilities: error, io, constants, validation
│   ├── cqrs/     # CQRS abstractions: Command, Query, handlers, buses
│   └── domain/   # Domain core: events, aggregate roots
├── utils/        # Utility classes (e.g., Snowflake ID generator)
```

## Quick Start

### 1. Add Dependency

Add the dependency to your `pom.xml` as shown in the Installation section above.

### 2. (Optional) Configure Thread Pool

In your `application.yaml`:

```yaml
soda:
  ddd:
    cqrs:
      async:
        core-pool-size: 8
        max-pool-size: 16
        queue-capacity: 100
        thread-name-prefix: "cqrs-async-"
```

If not configured, the starter will use default parameters.

### 3. Use CQRS Facade

```java
@Autowired
private BusFacade busFacade;

// Synchronous command
busFacade.sendCommand(command);
// Asynchronous command
busFacade.sendAsyncCommand(command);
// Query
busFacade.sendQuery(query);
```

### 4. Create Your First Command

```java
public class CreateUserCommand extends BaseCommand {
    private String username;
    private String email;
    
    // getters and setters
}

@Component
public class CreateUserCommandHandler implements CommandHandler<CreateUserCommand> {
    @Override
    public void handle(CreateUserCommand command) {
        // Your business logic here
        System.out.println("Creating user: " + command.getUsername());
    }
}
```

### 5. Create Your First Query

```java
public class GetUserQuery extends BaseQuery<User> {
    private String username;
    
    // getters and setters
}

@Component
public class GetUserQueryHandler implements QueryHandler<GetUserQuery, User> {
    @Override
    public User handle(GetUserQuery query) {
        // Your query logic here
        return new User(query.getUsername());
    }
}
```

## Configuration

### Thread Pool Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `soda.ddd.cqrs.async.core-pool-size` | 8 | Core thread pool size |
| `soda.ddd.cqrs.async.max-pool-size` | 16 | Maximum thread pool size |
| `soda.ddd.cqrs.async.queue-capacity` | 100 | Queue capacity |
| `soda.ddd.cqrs.async.thread-name-prefix` | "cqrs-async-" | Thread name prefix |

### JSON Serialization Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `soda.event.serialization.circular-reference-handler` | `IGNORE` | Circular reference handling strategy: `IGNORE` (ignore circular references), `ERROR` (throw error), `RETAIN` (retain circular references) |
| `soda.event.serialization.fail-on-self-references` | `false` | Whether to fail on self references during serialization |

## Extension & Customization

- **Thread pool parameters**: Override via `soda.ddd.cqrs.async.*` configuration.
- **Custom thread pool**: Define your own `@Bean("cqrsAsyncExecutor")` to override the default.
- **Custom BusFacade**: Define your own `@Bean` to override the starter's BusFacade.
- **Command/Query/Event Handlers**: Implement the corresponding interfaces; they will be auto-registered.

## Examples

### Domain Event Example

```java
public class UserCreatedEvent extends AbstractDomainEvent {
    private String username;
    
    public UserCreatedEvent(String username) {
        this.username = username;
    }
    
    // getters
}

@Component
public class UserCreatedEventHandler implements EventHandler<UserCreatedEvent> {
    @Override
    public void handle(UserCreatedEvent event) {
        // Handle user created event
        System.out.println("User created: " + event.getUsername());
    }
}
```

### Error Handling Example

```java
public class UserNotFoundException extends BaseException {
    public UserNotFoundException(String username) {
        super(BaseErrorCode.USER_NOT_FOUND, "User not found: " + username);
    }
}
```

## API Reference

### Core Classes

- `BusFacade`: Main facade for CQRS operations
- `BaseCommand`: Base class for commands
- `BaseQuery`: Base class for queries
- `BaseException`: Base exception class
- `AbstractAggregateRoot`: Base class for aggregate roots
- `AbstractDomainEvent`: Base class for domain events

### Interfaces

- `CommandHandler<T>`: Interface for command handlers
- `QueryHandler<T, R>`: Interface for query handlers
- `EventHandler<T>`: Interface for event handlers

## Migration Guide

### From scoda-ddd-starter to soda-core

If you're upgrading from scoda-ddd-starter:

1. Update your dependency artifactId to `soda-core-base` (plus `soda-event-redis` if you use the Redis event bus)
2. Update your dependency version to `17.0.0`
3. No breaking changes are expected
4. Review the changelog for any new features

## Build and Compilation

### Local Build

This project uses Maven for building and supports multiple Spring Boot versions through different POM configurations.

#### Build for Different Spring Boot Versions

The project provides separate POM files for different Spring Boot versions. To build for a specific version, copy the corresponding POM file to `pom.xml`:

```bash
# Spring Boot 2.3.12.RELEASE (Java 8)
cp pom-2.3.xml pom.xml
mvn clean package source:jar javadoc:jar

# Spring Boot 2.7.18 (Java 8)
cp pom-2.7.xml pom.xml
mvn clean package source:jar javadoc:jar

# Spring Boot 3.2.12 (Java 17) - Default
mvn clean package source:jar javadoc:jar
```

#### Skip Tests

```bash
# Skip tests during compilation and packaging
mvn clean package source:jar javadoc:jar -DskipTests

# Skip tests during installation
mvn clean install -DskipTests
```

#### Generate Documentation

```bash
# Generate Javadoc documentation
mvn javadoc:javadoc

# Generate source and documentation JARs
mvn clean package source:jar javadoc:jar
```

#### POM File Structure

The project includes the following POM files for different Spring Boot versions:

- `pom.xml` - Default configuration for Spring Boot 3.2.12 (Java 17)
- `pom-2.3.xml` - Configuration for Spring Boot 2.3.12.RELEASE (Java 8)
- `pom-2.7.xml` - Configuration for Spring Boot 2.7.18 (Java 8)

### Deploy to Maven Central

```bash
# Deploy snapshot version to OSS Sonatype
mvn clean deploy

# Deploy release version (requires GPG signing)
mvn clean deploy -Prelease
```

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### Development Setup

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

Highlights of `17.0.0` (the first release under the Java-baseline versioning scheme):

- Single jar supporting both Spring Boot 3.2.x (Jackson 2) and Spring Boot 4.x (Jackson 3)
- `EventSerializer` abstraction decoupling soda-event-redis from Jackson
- Minimum Java 17 (recommended 21)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

- **Documentation**: [GitHub Wiki](https://github.com/chenkangzeng1/soda-core/wiki)
- **Issues**: [GitHub Issues](https://github.com/chenkangzeng1/soda-core/issues)
- **Discussions**: [GitHub Discussions](https://github.com/chenkangzeng1/soda-core/discussions)

## Related Projects

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Domain-Driven Design](https://martinfowler.com/bliki/DomainDrivenDesign.html)

## Acknowledgments

- Spring Boot team for the excellent framework
- Domain-Driven Design community for the architectural patterns

## Version Compatibility

Versioning follows the Java-baseline scheme: `<Java-major>.<feature>.<patch>`.

| soda-core Version | Supported Spring Boot Version | Java Version | Status |
|-------------------|-------------------------------|--------------|--------|
| `17.x` (current)  | 3.2.x ~ 4.1.x (single jar)    | 17+ (recommended 21) | ✅ Active |
| `4.0.0`           | 4.x                           | 21           | ⛔ EOL (historical) |
| `2.1.x-boot32`    | 3.2.x                         | 8            | ⛔ EOL (`2.1.1-boot32` is POM-only on Central, use `2.1.0-boot32`) |
| `2.0.0` / `2.1.0` | 2.x                           | 8            | ⛔ EOL |
