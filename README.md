# MCHost

Hospede um servidor de Minecraft **no seu celular Android** (ou num PC com Linux) e controle tudo por um painel com botões, sem digitar comandos.

O MCHost é um app único: ele traz o Termux por baixo e um painel web (MCPanel) por cima, no estilo dos painéis de hospedagem.

> **Em teste.** Feito para uso próprio e entre amigos. Pode ter erros. Faça backup do seu mundo.

## Baixar

Vá em [**Releases**](../../releases) e baixe:

| Arquivo | Para quê |
|---|---|
| `MCHost-app-unico.apk` | Celular Android (arm64) |
| `mcpanel-pc-install.sh` | PC com Ubuntu ou Debian |

Baixe **somente daqui**. O APK é assinado com uma chave de teste pública, então um APK vindo de outro lugar pode ser falso.

## Instalar no celular

1. Baixe o `MCHost-app-unico.apk` e abra. O Android vai pedir para permitir "instalar apps desconhecidos".
2. Abra o **MCHost** e siga os botões da tela. Na primeira vez ele baixa o Java e prepara tudo (precisa de internet e alguns minutos).
3. Na aba **Versões**, escolha o tipo e a versão do servidor. Depois toque em **Iniciar**.

Observações:
- O app usa o mesmo identificador do Termux. Se você já tem o Termux instalado, o Android não deixa instalar os dois ao mesmo tempo.
- Deixe o celular na tomada e tire a restrição de bateria do app, senão o Android pode fechar o servidor.
- Celular esquenta: use `view-distance` baixo (6 a 8).

## Instalar no PC (Ubuntu / Debian)

```
sudo bash mcpanel-pc-install.sh
```

Pode rodar de novo para atualizar: não apaga mundo, mods, backups nem configurações. Depois abra `http://IP-DO-PC:8080` no navegador.

## O que o painel faz

- **Console** com gráficos de CPU e RAM, e botões Iniciar / Reiniciar / Parar
- **Versões**: Vanilla, Paper, Purpur, Leaves, Spigot, Fabric, Forge e NeoForge, com lista de versões e builds
- **Mods / Plugins**, **Arquivos** (editor, copiar, mover, zip) e **Propriedades** do servidor
- **Jogadores**: whitelist, ban, kick, op
- **Backups** manuais e agendados, e **Agendamentos** de tarefas
- **Rede**: endereços da rede local e túneis do [playit.gg](https://playit.gg) para amigos de fora
- **Bedrock**: instala e configura Geyser + Floodgate para quem joga no celular ou console
- **Subusuários** com permissões, login por Discord e registro de **Atividade**

O painel roda um servidor por vez.

## Créditos e licença

O MCHost é um fork do [Termux](https://github.com/termux/termux-app) e segue a mesma licença, [GPLv3](LICENSE.md). Todo o crédito do terminal e do ambiente Linux é do projeto Termux.

Os nomes e logotipos dos tipos de servidor (Paper, Spigot, Fabric, Forge e outros) pertencem aos respectivos projetos. O MCHost não é afiliado à Mojang, à Microsoft nem a nenhum deles.
