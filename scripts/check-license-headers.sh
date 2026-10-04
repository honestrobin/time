#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Fails if a source file lacks an SPDX licence header in its first three lines: MIT for the API
# client, which runs inside other people's software (decision record 0019), AGPL for the rest.
set -euo pipefail
cd "$(dirname "$0")/.."

missing=0
while IFS= read -r -d '' f; do
  expected="AGPL-3.0-only"
  case "$f" in packages/api-client/*) expected="MIT" ;; esac
  if ! head -n 3 "$f" | grep -q "SPDX-License-Identifier: $expected"; then
    echo "Missing SPDX header ($expected): $f"
    missing=1
  fi
done < <(git ls-files -z --cached --others --exclude-standard \
  '*.kt' '*.kts' '*.ts' '*.tsx' '*.css' '*.sql' '*.sh' '*.mjs' \
  ':!:backend/src/generated/**' ':!:packages/api-client/src/schema.d.ts' ':!:**/*.d.ts' ':!:**/vite-env.d.ts')

if [ "$missing" -ne 0 ]; then
  echo "Add '// SPDX-License-Identifier: AGPL-3.0-only' (MIT under packages/api-client), in the comment style of the file, at the top."
  exit 1
fi
echo "All source files carry an SPDX header."
