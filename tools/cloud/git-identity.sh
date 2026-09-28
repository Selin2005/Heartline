#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
# SessionStart hook: commits in this repository are always authored by the owner (see CLAUDE.md).
cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}" || exit 0
git config user.name "Selin"
git config user.email "61732050+Selin2005@users.noreply.github.com"
