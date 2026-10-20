# Leitor Reflow

Leitor de PDF para Android com refluxo de texto (como o ezPDF Reader) e só duas funções extras: tamanho da fonte e modo dia/noite.

O PDF precisa ter camada de texto. PDFs só de imagem devem passar por OCR antes.

## Como gerar o .apk

**Opção 1: Android Studio**
1. Abra esta pasta no Android Studio e aguarde a sincronização do Gradle.
2. Menu Build > Build Bundle(s) / APK(s) > Build APK(s).
3. O arquivo fica em `app/build/outputs/apk/debug/app-debug.apk`.

**Opção 2: GitHub Actions (sem instalar nada)**
1. Crie um repositório no GitHub e envie o conteúdo desta pasta.
2. Abra a aba Actions, rode "Gerar APK" e baixe `LeitorReflow-apk` ao final.

Para instalar, copie o `app-debug.apk` para o celular e permita "instalar apps desconhecidos".

## Como funciona
- `Reflow.kt`: junta linhas em parágrafos, remove hifenização de fim de linha e números de página soltos.
- `MainActivity.kt`: abre o PDF, extrai o texto página a página (PdfBox-Android) e mostra numa lista com rolagem.
- Dia/noite e tamanho da fonte ficam salvos entre aberturas.
