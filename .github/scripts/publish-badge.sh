#!/usr/bin/env bash
# Publie un fichier sur la branche orpheline `badges` sans effacer les autres badges.
# Usage : publish-badge.sh <fichier local> <nom sur la branche> <message de commit>
# La branche reste un commit unique (pas d'historique) ; le dépôt de travail n'est pas touché :
# l'arbre est construit dans un index temporaire à partir du contenu actuel de la branche.
set -euo pipefail

src="$1"; name="$2"; msg="$3"
export GIT_INDEX_FILE="${RUNNER_TEMP:-/tmp}/badges.index"
rm -f "$GIT_INDEX_FILE"

current=""
if git fetch -q --depth=1 origin badges 2>/dev/null; then
  git read-tree FETCH_HEAD
  current="$(git rev-parse -q --verify "FETCH_HEAD:$name" || true)"
else
  git read-tree --empty
fi

blob="$(git hash-object -w "$src")"
if [ "$blob" = "$current" ]; then
  echo "Badge $name inchangé."
  exit 0
fi
git update-index --add --cacheinfo "100644,$blob,$name"
tree="$(git write-tree)"
commit="$(git commit-tree "$tree" -m "$msg")"
git push --force origin "$commit:refs/heads/badges"
echo "Badge $name publié."
