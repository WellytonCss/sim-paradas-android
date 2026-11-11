# SIM Paradas — app Android (nativo)

App de campo do SIM Paradas · AMAGGI, em Kotlin + Jetpack Compose. Usa o mesmo banco (Supabase) e os mesmos logins do site.

**Funções:** Programação do dia • Apontamento de avanço/horas • RDO com fotos • Painel (avanço, Curva S, setores) • funciona sem internet (fila de envio automática).

## Assinatura
A chave do app fica nos segredos do repositório (`ANDROID_KEYSTORE` em base64 e `ANDROID_KEY_PASSWORD`), nunca no código.

## Como gerar o APK
Todo envio para o ramo `main` compila o app no GitHub Actions. O APK fica em **Releases** (e em Actions → execução → Artifacts).

## Como instalar
1. Baixe o `SIM-Paradas-1.0.N.apk` no celular (ou mande pelo WhatsApp/Drive).
2. Toque no arquivo e permita "instalar apps desta fonte" na primeira vez.
3. Para atualizar, instale a versão nova por cima (a assinatura é sempre a mesma; os dados não se perdem).
