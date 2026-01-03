# Docker Setup Guide

## Prerequisites
- Docker and Docker Compose installed
- Authorize.Net credentials (optional for testing)

## Initial Setup

### 1. Create .env file

Create a `.env` file from the template:

```bash
cp .env.example .env
```

Then edit `.env` and update with your actual credentials:
- Database passwords
- Authorize.Net API credentials
- Webhook signature key

**Important**: The `.env` file is git-ignored and should never be committed. See [SECRETS.md](SECRETS.md) for security best practices.

### 2. Start Services
```bash
docker-compose up -d
```

This will start:
- PostgreSQL database on port 5432
- Payment Gateway Application on port 8080

### 2. Check Services Status
```bash
docker-compose ps
```

### 3. View Logs
```bash
# All services
docker-compose logs -f

# Application only
docker-compose logs -f app

# Database only
docker-compose logs -f postgres
```

### 4. Access Services

- **Application**: http://localhost:8080
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/v3/api-docs
- **Health Check**: http://localhost:8080/actuator/health
- **PostgreSQL**: localhost:5432

### 5. Stop Services
```bash
docker-compose down
```

### 6. Stop and Remove Volumes (Clean Slate)
```bash
docker-compose down -v
```

## Environment Variables

All environment variables are managed via the `.env` file. Docker Compose automatically loads variables from `.env`.

**Required variables:**
- `DATABASE_PASSWORD` - PostgreSQL password
- `POSTGRES_PASSWORD` - PostgreSQL password (should match DATABASE_PASSWORD)
- `AUTHORIZE_NET_API_LOGIN_ID` - Your Authorize.Net API login ID
- `AUTHORIZE_NET_TRANSACTION_KEY` - Your Authorize.Net transaction key
- `AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY` - Webhook signature key

**Optional variables:**
- `AUTHORIZE_NET_ENVIRONMENT` - SANDBOX (default) or PRODUCTION
- `SERVER_PORT` - Application port (default: 8080)

See `.env.example` for all available variables and [SECRETS.md](SECRETS.md) for security guidelines.

## Database Access

### Connect to PostgreSQL
```bash
docker-compose exec postgres psql -U postgres -d payment_gateway
```

### View Database Tables
```sql
\dt
```

### Check Flyway Migrations
```sql
SELECT * FROM flyway_schema_history;
```

## Rebuilding

If you make code changes:

```bash
# Rebuild and restart
docker-compose up -d --build

# Or rebuild specific service
docker-compose build app
docker-compose up -d app
```

## Troubleshooting

### Database Connection Issues
- Ensure PostgreSQL is healthy: `docker-compose ps`
- Check logs: `docker-compose logs postgres`
- Verify network: `docker network ls`

### Application Won't Start
- Check application logs: `docker-compose logs app`
- Verify database is ready before app starts
- Check health endpoint: `curl http://localhost:8080/actuator/health`

### Port Already in Use
- Change ports in `docker-compose.yml`
- Or stop conflicting services

## Development Workflow

1. Make code changes
2. Rebuild: `docker-compose build app`
3. Restart: `docker-compose restart app`
4. Or use hot reload (if configured)

## Production Considerations

For production:
- Use environment-specific configuration
- Set strong database passwords
- Use secrets management
- Configure proper resource limits
- Set up monitoring and logging
- Use production-grade PostgreSQL configuration

