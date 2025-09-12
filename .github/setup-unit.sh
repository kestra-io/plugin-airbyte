API="${API:-http://localhost:8000/api/v1}"
AUTH="airbyte:password"
WORKSPACE="Kestra Test Workspace"
SRC_NAME="Dummy Sample Data"
DST_NAME="Dummy Local JSON"
CONN_NAME="Test Connection"
DEST_PATH="/local"

# defs
SRC_DEF="dfd88b22-b603-4c3d-aad7-3701784586b1"
DST_DEF="a625d593-bba5-4a1c-a53d-2d246268a816"

# source config
SAMPLE_CFG=$(jq -n \
  --argjson always_updated true --argjson count 1000 --argjson parallelism 1 \
  --argjson records_per_slice 1000 --argjson seed 42 \
  '{always_updated:$always_updated,count:$count,parallelism:$parallelism,records_per_slice:$records_per_slice,seed:$seed}')

# deps
for c in curl jq docker; do command -v "$c" >/dev/null || exit 1; done

# start cluster
docker compose -f docker-compose-ci.yml up --quiet-pull -d

# helper
api() { local p="$1"; shift || true; curl -sS --fail -u "$AUTH" -H "Content-Type: application/json" "$@" "${API}${p}"; }

# wait api
until api "/health" | jq -e '.available==true' >/dev/null; do sleep 5; done
until [[ "$(api "/source_definitions/list" -d '{}' | jq '.sourceDefinitions|length')" -gt 0 ]]; do sleep 5; done

# workspace
WS=$(api "/workspaces/list" -d '{}' | jq -r --arg n "$WORKSPACE" '.workspaces[]?|select(.name==$n)|.workspaceId' | head -n1)
[[ -n "${WS:-}" ]] || WS=$(api "/workspaces/create" -d "{\"name\":\"$WORKSPACE\"}" | jq -r '.workspaceId')

# source
SRC=$(api "/sources/create" -d "{
  \"name\":\"$SRC_NAME\",\"workspaceId\":\"$WS\",
  \"sourceDefinitionId\":\"$SRC_DEF\",\"connectionConfiguration\":$SAMPLE_CFG}" | jq -r '.sourceId')

# schema
DISC=$(api "/sources/discover_schema" -d "{\"sourceId\":\"$SRC\"}")
if [[ $(echo "$DISC" | jq '.catalog.streams|length') -eq 0 ]]; then
  CAT=$(jq -n '{streams:[{stream:{name:"dummy",jsonSchema:{type:"object",properties:{id:{type:"integer"}}}},config:{selected:true,syncMode:"full_refresh",destinationSyncMode:"overwrite"}}]}')
else
  CAT=$(echo "$DISC" | jq '{streams:(.catalog.streams|map({stream:.stream,config:{selected:true,syncMode:"full_refresh",destinationSyncMode:"overwrite"}}))}')
fi

# destination
DST=$(api "/destinations/create" -d "{
  \"name\":\"$DST_NAME\",\"workspaceId\":\"$WS\",
  \"destinationDefinitionId\":\"$DST_DEF\",\"connectionConfiguration\":{\"destination_path\":\"$DEST_PATH\"}}" | jq -r '.destinationId')

# connection
CONN=$(jq -n --arg s "$SRC" --arg d "$DST" --arg n "$CONN_NAME" --argjson c "$CAT" \
  '{sourceId:$s,destinationId:$d,syncCatalog:$c,scheduleType:"manual",status:"active",name:$n}' \
  | api "/connections/create" -d @- | jq -r '.connectionId')

# config file
mkdir -p src/test/resources
cat > src/test/resources/application-test.yml <<EOF
airbyte:
  api:
    url: ${API%/api/v1}
    username: airbyte
    password: password
  connectionId: "$CONN"
EOF

echo "Airbyte connction ready - connectionId=$CONN"
