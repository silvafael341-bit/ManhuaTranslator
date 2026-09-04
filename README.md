# Manhua Translator

Aplicativo Android para leitura de páginas de manhua/manga e tradução automática para português.

## Pipeline

Imagem → OCR ML Kit → detecção de regiões → tradução ML Kit → limpeza/renderização → leitor.

## OCR

Suporta chinês, japonês, coreano e texto latino. A análise tenta 0°, 90° e 270° para melhorar a detecção de texto vertical.

## Tradução

Usa os modelos do ML Kit Translation e mantém tradutores reutilizáveis durante a sessão.

## Renderização

A tradução é desenhada dentro de uma área oval conservadora ao redor da região detectada, com quebra automática de linhas e ajuste de tamanho da fonte.

## Build

Projeto Android/Kotlin com AGP 8.9.2, Kotlin 2.1.20, compileSdk 35, minSdk 26 e Gradle 8.11.1.

A versão atual é a primeira base integrada e compilável do aplicativo. O refinamento de máscaras e qualidade de tradução pode continuar sem alterar o fluxo principal.
