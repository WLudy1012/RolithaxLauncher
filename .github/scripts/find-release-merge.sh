#!/usr/bin/env bash
set -euo pipefail

git log --first-parent --merges --format='%H%x09%P%x09%s' "$1" |
  awk -F '\t' -v head="$2" -v subject="release: 发布 $3" '
    {
      split($2, parents, " ")
      if ($3 == subject && parents[2] == head && !found) {
        print $1
        found = 1
      }
    }
  '
