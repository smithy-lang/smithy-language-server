#!/usr/bin/env bash

set -eu

echo $PGP_SECRET | base64 --decode > gpg_key

gpg --import  --no-tty --batch --yes gpg_key

rm gpg_key

mill lsp.publish \
      --sonatypeCreds $SONATYPE_USERNAME:$SONATYPE_PASSWORD \
      --signed true \
      --release true \
      --readTimeout 600000 \
      --awaitTimeout 600000 \
      --gpgArgs --passphrase=$PGP_PASSPHRASE,--no-tty,--pinentry-mode,loopback,--batch,--yes,-a,-b
