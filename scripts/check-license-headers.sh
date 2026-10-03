#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Fails if a source file lacks an SPDX licence header in its first three lines.
set -euo pipefail
cd "$(dirname "$0")/.."

missing=0
while IFS= read -r -d '' f; do
  if ! head -n 3 "$f" | grep -q "SPDX-License-Identifier: AGPL-3.0-only"; then
    echo "Missing SPDX header: $f"
    missing=1
  fi
done < <(git ls-files -z --cached --others --exclude-standard \
  '*.kt' '*.kts' '*.ts' '*.tsx' '*.css' '*.sql' '*.sh' '*.mjs' \
  ':!:backend/src/generated/**' ':!:packages/api-client/src/schema.d.ts' ':!:**/*.d.ts' ':!:**/vite-env.d.ts')

if [ "$missing" -ne 0 ]; then
  echo "Add '// SPDX-License-Identifier: AGPL-3.0-only' (or the comment style of the file) at the top."
  exit 1
fi
echo "All source files carry an SPDX header."
