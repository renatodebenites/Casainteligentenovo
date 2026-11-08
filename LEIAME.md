# Jarvis · Casa Inteligente — APK pelo GitHub

## Como gerar o APK
1. Crie um repositório no GitHub e envie **todo** o conteúdo desta pasta (inclusive `.github/`).
2. Vá em **Actions → Build APK → Run workflow** (ou faça um push na branch `main`).
3. Ao terminar (~5 min), abra a execução e baixe **jarvis-apk** em *Artifacts*.
   - `jarvis-debug.apk` e `jarvis-release.apk` — ambos instalam no celular e na Google TV.

## Atualizar a interface
Substitua só `www/index.html` (e os ícones, se mudar) e faça push.
O workflow copia `www/` para `android/app/src/main/assets/www/` automaticamente.

## Assinatura própria (opcional)
Em *Settings → Secrets → Actions* crie: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Sem eles, o release é assinado com a chave debug (instala normalmente, mas não serve para Play Store).

## Ícones / banner (já incluídos)
- Ícone do app (celular, instalador e menu): `res/mipmap-*/ic_launcher*.png` + adaptativo `mipmap-anydpi-v26`
- Banner Google TV / Android TV (320x180dp): `res/drawable-xhdpi/banner.png` (640x360)
