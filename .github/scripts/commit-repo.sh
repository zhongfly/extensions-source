#!/bin/bash
set -e

rsync -I -a --delete --exclude .git --exclude .gitignore --exclude README.md --exclude repo.json ../main/repo/ .
git config --global user.email "41898282+github-actions[bot]@users.noreply.github.com"
git config --global user.name "github-actions[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push
else
    echo "No changes to commit"
fi
