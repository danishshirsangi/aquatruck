#!/usr/bin/env bash
set -euo pipefail
DATABASE_ID="ai-studio-android-aquatrac-ae0f6f83-854d-4712-bbed-b747ec9132c0"
echo "Active gcloud project:"
gcloud config get-value project
gcloud firestore fields ttls update expireAt \
  --collection-group=location_points \
  --enable-ttl \
  --database="$DATABASE_ID"
echo "TTL policy requested. Firestore applies deletion asynchronously."
