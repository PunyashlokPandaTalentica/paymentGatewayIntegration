# Auth0 Quick Reference

## Environment Variables

```bash
# Auth0 Configuration
OAUTH2_ISSUER_URI=https://YOUR_TENANT.us.auth0.com/
OAUTH2_AUDIENCE=https://api.paymentgateway.com
SECURITY_ENABLED=true
```

## Get Token (cURL)

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

## Use Token (cURL)

```bash
curl -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  http://localhost:8080/v1/orders
```

## Using the Helper Script

```bash
./scripts/get-auth0-token.sh
```

The script will prompt you for:
- Auth0 Domain
- Client ID
- Client Secret
- API Audience

## Auth0 Dashboard URLs

- **APIs**: https://manage.auth0.com/dashboard/us/YOUR_TENANT/apis
- **Applications**: https://manage.auth0.com/dashboard/us/YOUR_TENANT/applications
- **Test Tokens**: Go to API → Test tab → Copy Token


