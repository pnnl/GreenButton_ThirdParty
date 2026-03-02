#!/bin/bash
# OAuth Proxy Test Script: ThirdParty <-> DataCustodian
# This script simulates the full OAuth code flow and verifies XML data retrieval.

# Configurable variables
DC_HOST="localhost:8080"
TP_HOST="localhost:8080"
CLIENT_ID="third_party"
CLIENT_SECRET="secret"
REDIRECT_URI="http://$TP_HOST/ThirdParty/espi/1_1/OAuthCallBack"
# Optional: privacy level to request (full, medium, low, high). Empty = none
PRIVACY_LEVEL="full"
# Optional: additional explicit scope. Leave empty to use client-registered defaults
EXTRA_SCOPE=""
if [ -n "$PRIVACY_LEVEL" ]; then
  SCOPE="privacy:$PRIVACY_LEVEL"
  if [ -n "$EXTRA_SCOPE" ]; then
    SCOPE="$SCOPE%20$EXTRA_SCOPE"
  fi
else
  SCOPE="$EXTRA_SCOPE"
fi

# Will build consent params after loading the actual confirm_access form
CONSENT_PARAMS=""
USERNAME="alan"
PASSWORD="koala"
USER_ID="1" # alan's id in DB
STATE="teststate-$(date +%s)"

COOKIE_JAR="/tmp/dc_oauth_cookies.txt"

cleanup() { rm -f "$COOKIE_JAR" >/dev/null 2>&1 || true; }
trap cleanup EXIT

set -e

# Step 1: ThirdParty initiates OAuth flow (simulate user clicking Connect My Data)
echo "[1] Initiating OAuth flow..."
AUTH_URL="http://$DC_HOST/DataCustodian/oauth/authorize?response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&state=$STATE"
if [ -n "$SCOPE" ]; then
  AUTH_URL="$AUTH_URL&scope=$SCOPE"
fi
echo "Authorization URL: $AUTH_URL"

# Step 2: Simulate user login and consent (manual step, or automate if endpoint allows)
echo "[2] Simulating user login (capturing JSESSIONID cookie)..."
LOGIN_RESP=$(curl -s -i -c "$COOKIE_JAR" -X POST "http://$DC_HOST/DataCustodian/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}")
echo "Login response: $LOGIN_RESP"

# Step 3: Simulate user consent and get authorization code (manual, or automate if possible)
echo "[3] Getting authorization URL from proxy and visiting it as logged-in user..."
AUTH_CODE_RESP=$(curl -s -X POST "http://$DC_HOST/DataCustodian/api/auth/authorize" \
  -H "Content-Type: application/json" \
  -d "{\"client_id\":\"$CLIENT_ID\",\"response_type\":\"code\",\"redirect_uri\":\"$REDIRECT_URI\",\"state\":\"$STATE\",\"scope\":\"$SCOPE\"}")
AUTH_URL_EXTRACT=$(echo "$AUTH_CODE_RESP" | grep -o 'http://[^"}]*')
echo "Authorization URL (to load consent form): $AUTH_URL_EXTRACT"

echo "[3.1] Attempting auto-approve by inspecting redirect (no follow)..."
# Do not follow redirect to the ThirdParty callback; just capture Location header
AUTO_CB=$(curl -s -S -b "$COOKIE_JAR" -c "$COOKIE_JAR" -D - -o /dev/null "$AUTH_URL_EXTRACT" | awk '/^Location:/ {print $2}' | tr -d '\r')
if echo "$AUTO_CB" | grep -q "code="; then
  CALLBACK_URL="$AUTO_CB"
  echo "Auto-approve callback URL: $CALLBACK_URL"
else
  # Load once without following to create session state
  CONFIRM_HTML=$(curl -s -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$AUTH_URL_EXTRACT" || true)
  # Parse all scope keys rendered by the server (e.g., name="scope.FB=..." or name="scope.privacy:full")
  SCOPE_KEYS=$(echo "$CONFIRM_HTML" | grep -o 'name="scope\.[^"]*"' | sed 's/^name="//;s/"$//' | sort -u)
  if [ -n "$SCOPE_KEYS" ]; then
    for k in $SCOPE_KEYS; do
      CONSENT_PARAMS="${CONSENT_PARAMS}&${k}=true"
    done
  else
    # Fallback: approve any explicitly requested scopes if page parsing failed
    if [ -n "$SCOPE" ]; then
      RAW_SCOPE=$(echo "$SCOPE" | sed 's/%20/ /g')
      for s in $RAW_SCOPE; do
        CONSENT_PARAMS="${CONSENT_PARAMS}&scope.${s}=true"
      done
    fi
  fi
fi

echo "[4] Submitting consent (user_oauth_approval=true)..."
if [ -z "$CALLBACK_URL" ]; then
  # Do not follow the 302 to the redirect_uri; just capture Location header
  CALLBACK_URL=$(curl -s -S -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
    -X POST "http://$DC_HOST/DataCustodian/oauth/authorize" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data "user_oauth_approval=true${CONSENT_PARAMS}&authorize=Submit&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&state=$STATE" \
    -D - -o /dev/null | awk '/^Location:/ {print $2}' | tr -d '\r')
fi
echo "Callback URL: $CALLBACK_URL"

AUTH_CODE=$(echo "$CALLBACK_URL" | grep -o 'code=[^&]*' | cut -d'=' -f2)
if [ -z "$AUTH_CODE" ]; then
  echo "Failed to extract authorization code from callback URL." >&2
  exit 1
fi
echo "Extracted authorization code: $AUTH_CODE"

echo "[5] Exchanging code for access token via proxy..."
TOKEN_RESP=$(curl -s -X POST "http://$DC_HOST/DataCustodian/api/auth/token" \
  -H "Content-Type: application/json" \
  -d "{\"grant_type\":\"authorization_code\",\"code\":\"$AUTH_CODE\",\"redirect_uri\":\"$REDIRECT_URI\",\"client_id\":\"$CLIENT_ID\",\"client_secret\":\"$CLIENT_SECRET\"}")
echo "Token response: $TOKEN_RESP"
ACCESS_TOKEN=$(echo "$TOKEN_RESP" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
RESOURCE_URI=$(echo "$TOKEN_RESP" | grep -o '"resourceURI":"[^"]*' | cut -d'"' -f4)

# Step 5: Use access token to fetch user XML data
echo "[6] Fetching user XML data..."
if [ -z "$ACCESS_TOKEN" ]; then
  echo "No access token found. Please check previous steps."
  exit 1
fi
FETCH_URL="$RESOURCE_URI"
if [ -z "$FETCH_URL" ] || ! echo "$FETCH_URL" | grep -q '^http'; then
  FETCH_URL="http://$DC_HOST/DataCustodian/espi/1_1/resource/RetailCustomer/$USER_ID/UsagePoint"
fi
USER_XML=$(curl -s -X GET "$FETCH_URL" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Accept: application/atom+xml, application/xml, application/json")
echo "User XML data:"
echo "$USER_XML"

echo "\nOAuth proxy test complete."
