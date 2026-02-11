#!/bin/bash
# Generate secrets.yaml from example file

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFESTS_DIR="$SCRIPT_DIR/../manifests"
EXAMPLE_FILE="$MANIFESTS_DIR/02-secrets.yaml.example"
OUTPUT_FILE="$MANIFESTS_DIR/02-secrets.yaml"

echo "Generating secrets.yaml..."

# Check if example file exists
if [ ! -f "$EXAMPLE_FILE" ]; then
    echo "Error: $EXAMPLE_FILE not found"
    exit 1
fi

# Check if secrets.yaml already exists
if [ -f "$OUTPUT_FILE" ]; then
    read -p "secrets.yaml already exists. Overwrite? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 0
    fi
fi

# Copy example file
cp "$EXAMPLE_FILE" "$OUTPUT_FILE"

echo "secrets.yaml generated at $OUTPUT_FILE"
echo ""
echo "⚠️  IMPORTANT: Please edit $OUTPUT_FILE and update the following:"
echo "  - OPENAI_API_KEY: Replace with your actual OpenAI API key"
echo "  - Database passwords (if needed for production)"
echo "  - MinIO credentials (if needed for production)"
echo ""
echo "After editing, apply with:"
echo "  kubectl apply -f $OUTPUT_FILE"
