# Auth0 Setup Guide

This guide explains how to configure and use Auth0 for JWT authentication with the Payment Gateway Integration service.

## Overview

The Payment Gateway service is configured as an OAuth2 Resource Server. It validates JWT tokens issued by Auth0 but does not issue tokens itself. You need to:

1. Set up an Auth0 account and application
2. Configure Auth0 API
3. Configure the Payment Gateway service to validate Auth0 tokens
4. Get tokens from Auth0
5. Use tokens to access the API

---

## Step 1: Set Up Auth0 Account

1. **Sign up for Auth0** (if you don't have an account):
   - Go to [https://auth0.com](https://auth0.com)
   - Sign up for a free account (or use existing account)

2. **Note your Auth0 Domain**:
   - After signing up, you'll get a domain like: `your-tenant.us.auth0.com`
   - This will be used in the configuration

---

## Step 2: Create Auth0 API

1. **Navigate to APIs**:
   - In Auth0 Dashboard, go to **Applications** → **APIs**
   - Click **Create API**

2. **Configure the API**:
   - **Name**: `Payment Gateway API` (or any name you prefer)
   - **Identifier**: `https://api.paymentgateway.com` (or your API identifier)
     - ⚠️ **Important**: This identifier will be used as the `audience` in token requests
   - **Signing Algorithm**: `RS256` (default, recommended)
   - Click **Create**

3. **Note the API Identifier**:
   - Save the identifier (audience) - you'll need it later

---

## Step 3: Create Auth0 Machine-to-Machine Application

For server-to-server communication (recommended for API access):

1. **Create Application**:
   - Go to **Applications** → **Applications**
   - Click **Create Application**
   - **Name**: `Payment Gateway Client` (or any name)
   - **Type**: Select **Machine to Machine Applications**
   - Click **Create**

2. **Authorize the Application**:
   - Select the API you created in Step 2 (`Payment Gateway API`)
   - Toggle **Authorize** to enable access
   - Under **Authorized Scopes**, you can add custom scopes if needed (optional)
   - Click **Authorize**

3. **Get Client Credentials**:
   - Go to the **Settings** tab of your Machine-to-Machine application
   - Note down:
     - **Client ID**
     - **Client Secret** (click "Show" to reveal)

---

## Step 4: Configure Payment Gateway Service

Configure your application to validate tokens from Auth0.

### Option A: Using Environment Variables (Recommended)

Create or update your `.env` file:

```bash
# Auth0 Configuration
OAUTH2_ISSUER_URI=https://YOUR_TENANT.us.auth0.com/
OAUTH2_AUDIENCE=https://api.paymentgateway.com

# Security (must be enabled for Auth0)
SECURITY_ENABLED=true
```

**Replace:**
- `YOUR_TENANT` with your Auth0 tenant domain (e.g., `dev-abc123`)
- `https://api.paymentgateway.com` with the API identifier you created in Step 2

### Option B: Using application.yml

Update `src/main/resources/application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://YOUR_TENANT.us.auth0.com/
          audience: https://api.paymentgateway.com

app:
  security:
    enabled: true
```

---

## Step 5: Get JWT Token from Auth0

### Method 1: Using cURL (Command Line)

```bash
curl --request POST \
  --url https://YOUR_TENANT.us.auth0.com/oauth/token \
  --header 'content-type: application/json' \
  --data '{
    "client_id": "YOUR_CLIENT_ID",
    "client_secret": "YOUR_CLIENT_SECRET",
    "audience": "https://api.paymentgateway.com",
    "grant_type": "client_credentials"
  }'
```

**Replace:**
- `YOUR_TENANT` with your Auth0 tenant domain
- `YOUR_CLIENT_ID` with your Machine-to-Machine application Client ID
- `YOUR_CLIENT_SECRET` with your Machine-to-Machine application Client Secret
- `https://api.paymentgateway.com` with your API identifier

**Response:**
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 86400
}
```

### Method 2: Using Postman

1. **Create a new request**:
   - Method: `POST`
   - URL: `https://YOUR_TENANT.us.auth0.com/oauth/token`

2. **Headers**:
   - `Content-Type: application/json`

3. **Body** (raw JSON):
   ```json
   {
     "client_id": "YOUR_CLIENT_ID",
     "client_secret": "YOUR_CLIENT_SECRET",
     "audience": "https://api.paymentgateway.com",
     "grant_type": "client_credentials"
   }
   ```

4. **Send request** - you'll receive the `access_token` in the response

### Method 3: Using Auth0 Test Token (Development Only)

For quick testing, you can use Auth0's test token feature:

1. Go to your API in Auth0 Dashboard
2. Click on **Test** tab
3. Select your Machine-to-Machine application
4. Click **Copy Token** - this gives you a test token

⚠️ **Note**: Test tokens are for development only and have limited validity.

---

## Step 6: Use JWT Token with API

Once you have the token, include it in API requests using the `Authorization` header:

### Using cURL

```bash
curl -X GET \
  http://localhost:8080/v1/orders \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

### Using Postman

1. **Authorization Tab**:
   - Type: `Bearer Token`
   - Token: Paste your `access_token`

2. **Or use Header**:
   - Key: `Authorization`
   - Value: `Bearer YOUR_ACCESS_TOKEN`

### Example: Create an Order

```bash
curl -X POST \
  http://localhost:8080/v1/orders \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: unique-key-123" \
  -d '{
    "amount": "100.00",
    "currency": "USD",
    "description": "Test order"
  }'
```

---

## Step 7: Verify Configuration

1. **Start your application**:
   ```bash
   mvn spring-boot:run
   # or
   docker-compose up
   ```

2. **Test without token** (should fail):
   ```bash
   curl http://localhost:8080/v1/orders
   # Expected: 401 Unauthorized
   ```

3. **Test with token** (should succeed):
   ```bash
   curl -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
     http://localhost:8080/v1/orders
   # Expected: 200 OK or appropriate response
   ```

---

## Troubleshooting

### Issue: "OAuth2 JWT configuration is missing"

**Solution**: Make sure you've set `OAUTH2_ISSUER_URI` in your environment or `application.yml`.

### Issue: "Invalid token" or 401 Unauthorized

**Possible causes:**
1. Token expired - get a new token
2. Wrong audience - ensure the token's `aud` claim matches your API identifier
3. Wrong issuer - verify `OAUTH2_ISSUER_URI` matches your Auth0 domain
4. Token not from Auth0 - ensure you're using an Auth0-issued token

**Check token contents** (decode at [jwt.io](https://jwt.io)):
- `iss` (issuer) should match: `https://YOUR_TENANT.us.auth0.com/`
- `aud` (audience) should match your API identifier

### Issue: "CORS error" when calling from browser

**Solution**: The application already has CORS configured, but ensure:
- Your frontend is making requests to the correct origin
- The `Authorization` header is included in allowed headers (already configured)

### Issue: Token works in Postman but not in application

**Check:**
1. Token format: Must be `Bearer YOUR_TOKEN` (with space)
2. Token expiration: Tokens expire after 24 hours by default
3. Network/firewall: Ensure your application can reach Auth0's JWKS endpoint

---

## Advanced Configuration

### Custom Scopes

If you want to use custom scopes:

1. **In Auth0 API**:
   - Go to your API → **Scopes** tab
   - Add custom scopes (e.g., `read:orders`, `write:orders`)

2. **In Machine-to-Machine Application**:
   - Authorize the scopes you need

3. **When requesting token**:
   ```json
   {
     "client_id": "YOUR_CLIENT_ID",
     "client_secret": "YOUR_CLIENT_SECRET",
     "audience": "https://api.paymentgateway.com",
     "grant_type": "client_credentials",
     "scope": "read:orders write:orders"
   }
   ```

### Audience Validation

The application can validate the audience claim. Ensure your API identifier matches:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          audience: https://api.paymentgateway.com
```

### Token Expiration

Auth0 tokens typically expire in 24 hours. For production:
- Implement token refresh logic in your client
- Or use longer-lived tokens (configure in Auth0 API settings)

---

## Security Best Practices

1. **Never commit tokens or secrets**:
   - Keep `.env` files out of version control
   - Use environment variables in production

2. **Use HTTPS in production**:
   - Auth0 requires HTTPS for production
   - Configure your application to use HTTPS

3. **Rotate secrets regularly**:
   - Periodically rotate Client Secrets in Auth0
   - Update your application configuration accordingly

4. **Use environment-specific Auth0 tenants**:
   - Separate tenants for development, staging, and production
   - Different API identifiers per environment

5. **Monitor token usage**:
   - Use Auth0 Dashboard to monitor API access
   - Set up alerts for suspicious activity

---

## Quick Reference

### Environment Variables

```bash
# Required for Auth0
OAUTH2_ISSUER_URI=https://YOUR_TENANT.us.auth0.com/
OAUTH2_AUDIENCE=https://api.paymentgateway.com
SECURITY_ENABLED=true
```

### Get Token (cURL)

```bash
curl -X POST https://YOUR_TENANT.us.auth0.com/oauth/token \
  -H "Content-Type: application/json" \
  -d '{
    "client_id": "YOUR_CLIENT_ID",
    "client_secret": "YOUR_CLIENT_SECRET",
    "audience": "https://api.paymentgateway.com",
    "grant_type": "client_credentials"
  }'
```

### Use Token (cURL)

```bash
curl -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  http://localhost:8080/v1/orders
```

---

## Additional Resources

- [Auth0 Documentation](https://auth0.com/docs)
- [Auth0 Machine-to-Machine Applications](https://auth0.com/docs/get-started/applications/machine-to-machine-apps)
- [Auth0 APIs](https://auth0.com/docs/get-started/apis)
- [JWT.io - Token Decoder](https://jwt.io)

---

## Support

If you encounter issues:
1. Check Auth0 Dashboard logs
2. Check application logs for authentication errors
3. Verify token contents at [jwt.io](https://jwt.io)
4. Ensure all environment variables are set correctly


