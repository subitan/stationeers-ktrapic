#!/usr/bin/env bash
# Makes the key and certificate the release workflow signs the plugin with, in a directory outside
# the repository, and prints the commands that store them as the "marketplace" environment's
# secrets. Run once, by the maintainer; keep the directory (or delete it once the secrets are set,
# since a new key can be made at any time). Needs openssl, which asks for the key's password.
#
#   intellij/make-signing-key.sh ~/ktrapic-signing
set -euo pipefail

dir=${1:?usage: make-signing-key.sh <new directory outside the repository>}
repo=$(cd "$(dirname "$0")/.." && pwd -P)
mkdir -p "$dir"
dir=$(cd "$dir" && pwd -P)
case "$dir/" in
  "$repo"/*)
    rmdir "$dir" 2>/dev/null || true
    echo "$dir is inside the repository; put the key somewhere it can't be committed" >&2
    exit 1
    ;;
esac
if [[ -e "$dir/private.pem" ]]; then
  echo "$dir already has a key" >&2
  exit 1
fi

# As JetBrains' plugin signing guide does: an encrypted 4096-bit RSA key, the key in the form the
# signer reads, and a self-signed certificate for it, valid for ten years.
openssl genpkey -aes-256-cbc -algorithm RSA -out "$dir/private_encrypted.pem" -pkeyopt rsa_keygen_bits:4096
openssl rsa -in "$dir/private_encrypted.pem" -out "$dir/private.pem"
openssl req -key "$dir/private.pem" -new -x509 -days 3650 -subj "/CN=KTrapIC" -out "$dir/chain.crt"
chmod 600 "$dir"/*.pem

cat <<EOT
Made $dir/private.pem and $dir/chain.crt. Store them, and the password you chose, as secrets of the
repository's "marketplace" environment:

  gh secret set CERTIFICATE_CHAIN --env marketplace < "$dir/chain.crt"
  gh secret set PRIVATE_KEY --env marketplace < "$dir/private.pem"
  gh secret set PRIVATE_KEY_PASSWORD --env marketplace

EOT
