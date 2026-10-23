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

## Como usar
- Toque no **centro da tela** para mostrar ou esconder a barra de botões (a tela fica cheia quando a barra está escondida).
- Todo arquivo abre no modo **Original**. Para ler em refluxo, toque em **Modo**.
- **Modo: Refluxo / Original**: alterna entre o texto reorganizado e as páginas como no PDF. No modo Original, A− e A+ ajustam o zoom (1x a 2x) e a tela rola para os lados quando ampliada.
- **Noite**: no modo Original, as cores das páginas são invertidas.

- **Links e índice clicável:** toque num link do PDF para ir à página (ou abrir o endereço). O botão Voltar do Android retorna ao ponto de onde você saiu.
- **Copiar texto:** no modo Refluxo, pressione e segure sobre o texto para marcar e use Copiar. Se a seleção direta não iniciar, abre uma janela com o texto da página para marcar, com o botão "Copiar página".
- **Páginas:** o botão alterna entre Vertical (rolagem contínua) e Horizontal (uma página por tela, deslizando para os lados, sem animação de virar). No modo Original com zoom, no formato Horizontal, o dedo move a página ampliada; para virar, encoste na borda e deslize de novo.
- **Número da página e nome do arquivo:** aparecem embaixo, nos dois modos, junto com o menu (o nome em letras menores, sem a extensão .pdf). Toque no centro da tela para mostrar ou esconder tudo.
- **Pinça:** feche ou abra dois dedos para dar zoom, nos dois modos. Os botões A− e A+ continuam funcionando (no modo Original, o zoom vai até 2,5x).
- **Páginas: Vertical / Horizontal:** no modo Horizontal, cada página ocupa a tela inteira e você vira deslizando para os lados, sem animação de página virando. No modo Refluxo, uma página longa rola para baixo dentro da própria tela. No modo Original ampliado, o deslize move a página e vira ao chegar na borda.
- **Número da página:** aparece embaixo, nos dois modos (por exemplo, 12 / 190).
- **Copiar texto no modo Original:** pressione e segure sobre uma palavra e arraste para marcar; solte e toque em **Copiar** na barrinha que aparece embaixo (ou em **Cancelar**). Funciona em PDFs com camada de texto; em PDFs só de imagem avisa que não há texto. A marcação vale dentro de uma página por vez.
- **Duas colunas no Refluxo:** a calha entre as colunas é detectada em cada página e o texto é lido coluna da esquerda e depois a da direita. Títulos em largura total continuam acima das colunas. Páginas de coluna única não mudam.
- **Carregamento:** o PDF é copiado uma vez para o cache e aberto por arquivo; no modo Original a primeira página aparece na hora (as demais são medidas em segundo plano), os links são lidos só das páginas que aparecem e as páginas já desenhadas ficam em cache.
- **Refluxo mais rápido:** ao abrir o modo Refluxo, o app extrai primeiro a página em que você está e só depois o resto do livro (as demais mostram "Carregando página…" por instantes). O texto extraído fica guardado no aparelho; da segunda vez em diante o mesmo PDF abre em Refluxo quase na hora.
- **Ir para a página:** toque no "Página X de N" (embaixo, com o menu visível), digite o número e toque em OK.
- **Buscar (🔍):** botão no menu superior. Digite a palavra ou frase; a busca ignora maiúsculas e minúsculas e, com a caixa **Diferenciar acentos** marcada (padrão), acha exatamente o que foi digitado ("ação" não acha "acao"); desmarcando, ignora também os acentos. Marcando **Palavra inteira**, só aparecem ocorrências isoladas ("ação" não acha "organização" nem "doação"). Lista as ocorrências com a página e um trecho. Toque num resultado para ir até a página, com a palavra destacada (no modo Original, o destaque some ao tocar na tela). A primeira busca num PDF grande demora mais; as seguintes são rápidas. PDFs só de imagem não têm texto para buscar.
- **Lembrar página:** reabre o arquivo na última página lida. Pode ser desligado.
- Ao trocar entre Refluxo e Original, você continua na mesma página.

## Como funciona
- `Reflow.kt`: junta linhas em parágrafos, remove hifenização de fim de linha e números de página soltos.
- `MainActivity.kt`: abre o PDF, extrai o texto página a página (PdfBox-Android) e mostra numa lista com rolagem.
- Dia/noite e tamanho da fonte ficam salvos entre aberturas.
