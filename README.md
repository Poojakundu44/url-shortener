# URL Shortener Service

A production-grade URL shortener built with Java 17, Spring Boot 3, Redis, and PostgreSQL.

## Tech Stack
- Java 17 / Spring Boot 3
- Spring Data JPA + H2 (dev) / PostgreSQL (prod)
- Redis (distributed cache)
- Docker + Docker Compose
- Maven

## Running locally

```bash
mvn spring-boot:run
```

## Running with Docker (full stack)

```bash
docker-compose up --build
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | /api/v1/urls | Create short URL |
| GET | /api/v1/urls/{shortCode} | Get URL details |
| GET | /{shortCode} | Redirect to original URL |
| DELETE | /api/v1/urls/{shortCode} | Deactivate URL |

## Example

```bash
POST /api/v1/urls
{
  "originalUrl": "https://github.com",
  "customAlias": "my-github"
}
```
