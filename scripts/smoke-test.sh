#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SUFFIX="$(date +%s)"
POLICYHOLDER_USER="policyholder_${SUFFIX}"
AGENT_USER="agent_${SUFFIX}"
ADMIN_USER="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin@12345}"
POLICY_IDEMPOTENCY_KEY="issue-policy-${SUFFIX}"
CLAIM_IDEMPOTENCY_KEY="submit-claim-${SUFFIX}"

json_field() {
  python3 -c '
import json
import sys

data = json.load(sys.stdin)
path = sys.argv[1].split(".")
for key in path:
    data = data[key]
print(data)
' "$1"
}

wait_for() {
  local url="$1"
  for _ in $(seq 1 60); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

post_json() {
  local url="$1"
  local body="$2"
  shift 2
  local response_file
  response_file="$(mktemp)"

  for attempt in $(seq 1 15); do
    if curl --fail-with-body -sS -X POST "$url" \
      -H "Content-Type: application/json" \
      "$@" \
      -d "$body" \
      -o "$response_file"; then
      cat "$response_file"
      rm -f "$response_file"
      return 0
    fi

    if [[ "$attempt" -lt 15 ]]; then
      sleep 2
    fi
  done

  echo "POST $url failed after retries. Last response:" >&2
  cat "$response_file" >&2 || true
  rm -f "$response_file"
  return 1
}

echo "Waiting for service health..."
wait_for "${BASE_URL}/actuator/health"
wait_for "${BASE_URL}/api/auth/health"
wait_for "http://localhost:8081/actuator/health"
wait_for "http://localhost:8082/actuator/health"
wait_for "http://localhost:8083/actuator/health"

echo "Registering a policyholder..."
register_response="$(post_json "${BASE_URL}/api/auth/register" \
  "{\"username\":\"${POLICYHOLDER_USER}\",\"password\":\"Policy@12345\"}")"
policyholder_token="$(printf '%s' "$register_response" | json_field accessToken)"
policyholder_id="$(printf '%s' "$register_response" | json_field userId)"

echo "Logging in as admin..."
admin_login="$(post_json "${BASE_URL}/api/auth/login" \
  "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASSWORD}\"}")"
admin_token="$(printf '%s' "$admin_login" | json_field accessToken)"

echo "Creating an agent..."
create_agent_response="$(post_json "${BASE_URL}/api/auth/admin/users" \
  "{\"username\":\"${AGENT_USER}\",\"password\":\"Agent@12345\",\"role\":\"AGENT\"}" \
  -H "Authorization: Bearer ${admin_token}")"
agent_id="$(printf '%s' "$create_agent_response" | json_field id)"

echo "Logging in as agent..."
agent_login="$(post_json "${BASE_URL}/api/auth/login" \
  "{\"username\":\"${AGENT_USER}\",\"password\":\"Agent@12345\"}")"
agent_token="$(printf '%s' "$agent_login" | json_field accessToken)"

echo "Issuing a policy..."
policy_response="$(post_json "${BASE_URL}/api/policies" \
  "{\"policyholderId\":${policyholder_id},\"assignedAgentId\":${agent_id},\"premium\":199.99,\"startDate\":\"2026-01-01\",\"endDate\":\"2027-12-31\"}" \
  -H "Authorization: Bearer ${agent_token}" \
  -H "Idempotency-Key: ${POLICY_IDEMPOTENCY_KEY}")"
policy_id="$(printf '%s' "$policy_response" | json_field id)"

echo "Submitting a claim..."
claim_response="$(post_json "${BASE_URL}/api/claims" \
  "{\"policyId\":\"${policy_id}\",\"description\":\"Broken windshield\",\"claimAmount\":350.00}" \
  -H "Authorization: Bearer ${policyholder_token}" \
  -H "Idempotency-Key: ${CLAIM_IDEMPOTENCY_KEY}")"
claim_id="$(printf '%s' "$claim_response" | json_field id)"

echo "Replaying the claim submission with the same idempotency key..."
claim_replay_response="$(post_json "${BASE_URL}/api/claims" \
  "{\"policyId\":\"${policy_id}\",\"description\":\"Broken windshield\",\"claimAmount\":350.00}" \
  -H "Authorization: Bearer ${policyholder_token}" \
  -H "Idempotency-Key: ${CLAIM_IDEMPOTENCY_KEY}")"
replayed_claim_id="$(printf '%s' "$claim_replay_response" | json_field id)"

if [[ "${replayed_claim_id}" != "${claim_id}" ]]; then
  echo "Expected idempotent claim replay to return ${claim_id}, got ${replayed_claim_id}" >&2
  exit 1
fi

echo "Verifying the claim as agent..."
post_json "${BASE_URL}/api/claims/${claim_id}/verify" \
  '{"notes":"Verified against active policy"}' \
  -H "Authorization: Bearer ${agent_token}" >/dev/null

echo "Approving the claim as admin..."
approval_response="$(post_json "${BASE_URL}/api/claims/${claim_id}/approve" \
  '{"notes":"Approved after review"}' \
  -H "Authorization: Bearer ${admin_token}")"
final_status="$(printf '%s' "$approval_response" | json_field status)"

if [[ "${final_status}" != "APPROVED" ]]; then
  echo "Expected APPROVED but got ${final_status}" >&2
  exit 1
fi

echo "Smoke test completed successfully."
