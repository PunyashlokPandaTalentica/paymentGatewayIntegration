#!/bin/bash

# Script to get JWT token from Auth0
# Usage: ./get-auth0-token.sh

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Auth0 Token Generator${NC}"
echo "======================"
echo ""

# Check if required tools are available
if ! command -v jq &> /dev/null; then
    echo -e "${YELLOW}Warning: jq is not installed. JSON output will not be formatted.${NC}"
    echo "Install jq: sudo apt-get install jq (or brew install jq on macOS)"
    JQ_AVAILABLE=false
else
    JQ_AVAILABLE=true
fi

# Prompt for Auth0 configuration
read -p "Enter Auth0 Domain (e.g., dev-abc123.us.auth0.com): " AUTH0_DOMAIN
read -p "Enter Client ID: " CLIENT_ID
read -sp "Enter Client Secret: " CLIENT_SECRET
echo ""
read -p "Enter API Audience (e.g., https://api.paymentgateway.com): " AUDIENCE

# Validate inputs
if [ -z "$AUTH0_DOMAIN" ] || [ -z "$CLIENT_ID" ] || [ -z "$CLIENT_SECRET" ] || [ -z "$AUDIENCE" ]; then
    echo -e "${RED}Error: All fields are required${NC}"
    exit 1
fi

# Construct the token endpoint URL
TOKEN_URL="https://${AUTH0_DOMAIN}/oauth/token"

echo ""
echo -e "${GREEN}Requesting token from Auth0...${NC}"
echo "URL: $TOKEN_URL"
echo ""

# Make the request
RESPONSE=$(curl -s -X POST "$TOKEN_URL" \
  -H "Content-Type: application/json" \
  -d "{
    \"client_id\": \"$CLIENT_ID\",
    \"client_secret\": \"$CLIENT_SECRET\",
    \"audience\": \"$AUDIENCE\",
    \"grant_type\": \"client_credentials\"
  }")

# Check if request was successful
if echo "$RESPONSE" | grep -q "access_token"; then
    # Extract token
    if [ "$JQ_AVAILABLE" = true ]; then
        TOKEN=$(echo "$RESPONSE" | jq -r '.access_token')
        EXPIRES_IN=$(echo "$RESPONSE" | jq -r '.expires_in')
        
        echo -e "${GREEN}✓ Token received successfully!${NC}"
        echo ""
        echo "Token expires in: ${EXPIRES_IN} seconds"
        echo ""
        echo -e "${GREEN}Access Token:${NC}"
        echo "$TOKEN"
        echo ""
        echo -e "${GREEN}Full Response:${NC}"
        echo "$RESPONSE" | jq '.'
        echo ""
        echo -e "${YELLOW}Usage example:${NC}"
        echo "curl -H \"Authorization: Bearer $TOKEN\" http://localhost:8080/v1/orders"
    else
        TOKEN=$(echo "$RESPONSE" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
        echo -e "${GREEN}✓ Token received successfully!${NC}"
        echo ""
        echo -e "${GREEN}Access Token:${NC}"
        echo "$TOKEN"
        echo ""
        echo -e "${GREEN}Full Response:${NC}"
        echo "$RESPONSE"
        echo ""
        echo -e "${YELLOW}Usage example:${NC}"
        echo "curl -H \"Authorization: Bearer $TOKEN\" http://localhost:8080/v1/orders"
    fi
else
    echo -e "${RED}✗ Error getting token${NC}"
    echo ""
    if [ "$JQ_AVAILABLE" = true ]; then
        echo "$RESPONSE" | jq '.'
    else
        echo "$RESPONSE"
    fi
    exit 1
fi


