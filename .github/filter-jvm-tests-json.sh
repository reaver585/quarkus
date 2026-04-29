#!/bin/bash

# Purpose: Prints a filtered version of matrix-jvm-tests.json
# Note: This script is only for CI and does therefore not aim to be compatible with BSD/macOS.

set -e -u -o pipefail
shopt -s failglob

export IFS=$'\n'

# path of this shell script
PRG_PATH=$( cd "$(dirname "$0")" ; pwd -P )

JSON=$(cat ${PRG_PATH}/matrix-jvm-tests.json)
if [[ "${GITHUB_REPOSITORY:-DEFINED_ON_CI}" != "quarkusio/quarkus" ]]; then
  # Filter out mac os from a matrix of build configurations.
  # The reason we do this is that running mac on a fork won't work; the fork won't have a self-hosted runner
  # See https://stackoverflow.com/questions/65384420/how-to-make-a-github-action-matrix-element-conditional
  JSON=$(echo -n "$JSON" | jq 'map(. | select(.["os-name"]!="macos-arm64-latest"))')
fi

JSON=$(echo "$JSON" | jq '
  map(
    . + {
      tag: (
        .name
        | ascii_downcase
        | gsub(" "; "-")
        | gsub("-+"; "-")
      )
    }
  )
')

# Step 0: resolve module list from impacted modules or full reactor.
if [ "$1" == '_all_' ]
then
  MODULES=$(${PRG_PATH}/list-reactor-modules.sh)
elif [ -z "$1" ]
then
  echo -n ''
  exit 0
else
  MODULES="$1"
fi

RUNTIME_MODULES=$(echo -n "$MODULES" | grep -Ev '^(integration-tests|tcks|docs)($|/.*)' || echo '')
INTEGRATION_TESTS=$(echo -n "$MODULES" | grep -E '^integration-tests/.+' | grep -Ev '^integration-tests/(devtools|gradle|maven|devmode|kubernetes)($|/.*)' || echo '')

if [ -z "$RUNTIME_MODULES" ] && [ -z "$INTEGRATION_TESTS" ]; then
  echo -n ''
  exit 0
fi

if [ -z "$RUNTIME_MODULES" ]; then
  JSON=$(echo -n "$JSON" | jq --arg category Runtime 'del( .[] | select(.category == $category) )')
else
  RUNTIME_SHARDS=${RUNTIME_SHARDS:-5}
  RUNTIME_SHARDS_FILE=$(mktemp)
  export RUNTIME_MODULES
  python3 - "$RUNTIME_SHARDS" <<'PY' > "$RUNTIME_SHARDS_FILE"
import json
import os
import sys

modules = [m for m in os.environ["RUNTIME_MODULES"].splitlines() if m.strip()]
shard_count = max(1, int(sys.argv[1]))

shards = [[] for _ in range(shard_count)]
for index, module in enumerate(modules):
    shards[index % shard_count].append(module)

print(json.dumps([
    {
        "index": i + 1,
        "modules": "-pl\\n" + ",".join(shard),
    }
    for i, shard in enumerate(shards)
    if shard
]))
PY
  JSON=$(echo -n "$JSON" | jq --arg category Runtime --slurpfile shards "$RUNTIME_SHARDS_FILE" '
      . as $matrix
      | [
          $matrix[] | select(.category != $category)
        ] + [
          $matrix[]
          | select(.category == $category and .["runs-on"] == true) as $template
          | $shards[0][] as $shard
          | $template + {
              name: ($template.name + " - shard " + ($shard.index | tostring)),
              tag: ($template.tag + "-shard-" + ($shard.index | tostring)),
              modules: $shard.modules
            }
        ]')
  rm "$RUNTIME_SHARDS_FILE"
fi

if [ -z "$INTEGRATION_TESTS" ]; then
  JSON=$(echo -n "$JSON" | jq --arg category Integration 'del( .[] | select(.category == $category) )')
else
  INTEGRATION_SHARDS=${INTEGRATION_SHARDS:-5}
  INTEGRATION_SHARDS_FILE=$(mktemp)
  export INTEGRATION_TESTS
  python3 - "$INTEGRATION_SHARDS" <<'PY' > "$INTEGRATION_SHARDS_FILE"
import json
import os
import sys

modules = [m for m in os.environ["INTEGRATION_TESTS"].splitlines() if m.strip()]
shard_count = max(1, int(sys.argv[1]))

shards = [[] for _ in range(shard_count)]
for index, module in enumerate(modules):
    shards[index % shard_count].append(module)

print(json.dumps([
    {
        "index": i + 1,
        "modules": "-pl\\n" + ",".join(shard),
    }
    for i, shard in enumerate(shards)
    if shard
]))
PY
  JSON=$(echo -n "$JSON" | jq --arg category Integration --slurpfile shards "$INTEGRATION_SHARDS_FILE" '
      . as $matrix
      | [
          $matrix[] | select(.category != $category)
        ] + [
          $matrix[]
          | select(.category == $category and .["runs-on"] == true) as $template
          | $shards[0][] as $shard
          | $template + {
              name: ($template.name + " - shard " + ($shard.index | tostring)),
              tag: ($template.tag + "-shard-" + ($shard.index | tostring)),
              modules: ("-f\\nintegration-tests\\n" + $shard.modules)
            }
        ]')
  rm "$INTEGRATION_SHARDS_FILE"
fi

echo "$JSON" | jq -c '{java: .}'
