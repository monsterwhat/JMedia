#!/usr/bin/env bash
#
# Documentation consistency check. Run from anywhere; paths resolve from the
# repository root (the parent of this script's directory).
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)"
cd "$ROOT"

README="README.md"
MATRIX="JMedia/com.playdeca.JMedia/docs/sync-matrix.md"
WORKFLOWS_DIR=".github/workflows"
failures=0

fail() {
    printf 'FAIL check-docs: %s\n' "$1" >&2
    failures=$((failures + 1))
}

for required in "$README" "$MATRIX"; do
    if [ ! -f "$required" ]; then
        fail "missing required file: $required"
    fi
done

if [ ! -d "$WORKFLOWS_DIR" ]; then
    fail "missing workflow directory: $WORKFLOWS_DIR"
fi

if [ -f "$README" ]; then
    # The version lives only in the pom. A hardcoded release version will drift.
    version_lines="$(grep -nE 'Version [0-9]+\.[0-9]+' "$README" | cut -d: -f1 || true)"
    while IFS= read -r line_number; do
        [ -n "$line_number" ] || continue
        fail "$README:$line_number states a hardcoded version; derive it from JMedia/com.playdeca.JMedia/pom.xml"
    done <<<"$version_lines"

    for claim in "serverless" "zero external dependencies" "50%" "42 API"; do
        if grep -niF -- "$claim" "$README" >/dev/null 2>&1; then
            fail "$README contains the banned claim \"$claim\""
        fi
    done

    # Local paths quoted in the README must exist. The mandated version line uses
    # the module name without its JMedia/ prefix, so try that prefix as a fallback.
    refs="$(grep -oE '(JMedia/)?com\.playdeca\.JMedia/[A-Za-z0-9._/-]+|\.github/[A-Za-z0-9._/-]+|scripts/[A-Za-z0-9._/-]+' "$README" | sort -u || true)"
    while IFS= read -r ref; do
        [ -n "$ref" ] || continue
        ref="$(printf '%s' "$ref" | sed -E 's/[.,;:)]+$//')"
        [ -n "$ref" ] || continue
        if [ -e "$ref" ]; then
            continue
        fi
        # The README quotes module-relative paths both with and without the
        # JMedia/ prefix; accept either form.
        if [ -e "JMedia/$ref" ]; then
            continue
        fi
        fail "$README references a missing path: $ref"
    done <<<"$refs"

    # The runnable jar is renamed by the build; README and release docs must use
    # the same name the workflows publish.
    workflow_jars="$(grep -hoE '[A-Za-z0-9._-]+-runner\.jar' "$WORKFLOWS_DIR"/*.yml 2>/dev/null | sort -u || true)"
    if [ -z "$workflow_jars" ]; then
        fail "no *-runner.jar asset name found in $WORKFLOWS_DIR/*.yml"
    fi

    if ! grep -qE '[A-Za-z0-9._-]+-runner\.jar' "$README"; then
        fail "$README does not name the runnable jar"
    fi

    doc_files=("$README")
    while IFS= read -r doc; do
        [ -n "$doc" ] || continue
        doc_files+=("$doc")
    done < <(find JMedia/com.playdeca.JMedia/docs -maxdepth 1 -type f -name '*.md' 2>/dev/null | sort || true)

    for doc in "${doc_files[@]}"; do
        [ -f "$doc" ] || continue
        doc_jars="$(grep -oE '[A-Za-z0-9._-]+-runner\.jar' "$doc" | sort -u || true)"
        while IFS= read -r jar; do
            [ -n "$jar" ] || continue
            if ! grep -qxF -- "$jar" <<<"$workflow_jars"; then
                published="$(printf '%s' "$workflow_jars" | tr '\n' ' ')"
                fail "$doc names asset \"$jar\" but workflows publish: ${published:-none}"
            fi
        done <<<"$doc_jars"
    done
fi

if [ "$failures" -ne 0 ]; then
    printf 'check-docs: %d problem(s) found\n' "$failures" >&2
    exit 1
fi

printf 'check-docs: OK (required docs present, paths resolve, no stale version or banned claims, asset name matches workflows)\n'
