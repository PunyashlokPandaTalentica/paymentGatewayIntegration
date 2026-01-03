# Secrets Management

## Environment Variables

This project uses `.env` files to manage secrets and configuration. **Never commit `.env` files to version control.**

## Setup

### 1. Create .env file

Copy the example file:
```bash
cp .env.example .env
```

### 2. Update values

Edit `.env` and update all placeholder values with your actual credentials:

```bash
# Required: Database credentials
DATABASE_PASSWORD=your_secure_password_here
POSTGRES_PASSWORD=your_secure_password_here

# Required: Authorize.Net credentials
AUTHORIZE_NET_API_LOGIN_ID=your_actual_api_login_id
AUTHORIZE_NET_TRANSACTION_KEY=your_actual_transaction_key
AUTHORIZE_NET_WEBHOOK_SIGNATURE_KEY=your_actual_signature_key
```

## Security Best Practices

### ✅ DO:
- Use strong, unique passwords for database
- Rotate credentials regularly
- Use different credentials for development/staging/production
- Keep `.env` file local only
- Use `.env.example` as a template (safe to commit)
- Use environment-specific `.env` files (`.env.development`, `.env.production`)

### ❌ DON'T:
- Commit `.env` files to git
- Share `.env` files via email or chat
- Use production credentials in development
- Hardcode secrets in code
- Store secrets in version control

## Environment-Specific Files

You can create environment-specific files:
- `.env.development` - Local development
- `.env.staging` - Staging environment
- `.env.production` - Production environment

Docker Compose will automatically use `.env` file if present.

## Production Deployment

For production:
1. Use a secrets management service (AWS Secrets Manager, HashiCorp Vault, etc.)
2. Inject secrets at runtime via environment variables
3. Use Docker secrets or Kubernetes secrets
4. Never store production secrets in files

## Getting Authorize.Net Credentials

1. Log in to your Authorize.Net merchant account
2. Navigate to Account → Settings → Security Settings → API Credentials
3. Create or view your API Login ID and Transaction Key
4. For webhooks, configure webhook signature key in webhook settings

## Database Password

For local development, you can use a simple password. For production:
- Use a strong password (minimum 16 characters)
- Include uppercase, lowercase, numbers, and special characters
- Store in a password manager
- Rotate regularly

## Verification

After setting up `.env`, verify it's working:

```bash
# Check that .env is ignored by git
git status
# .env should NOT appear in the list

# Test Docker Compose reads the file
docker-compose config
# Should show your values (not placeholders)
```

