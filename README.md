# HY Home

Launcher leve, sem rede e sem anúncios para o projetor **HY300-T** (Amlogic T950S, Android 13,
tela 800×480, ~1 GB de RAM). Feito para controle remoto (D-pad).

- Grade de apps com favoritos e apps ocultos (segure OK sobre um app).
- Na primeira abertura favorita SmartTube, MX Player, Netflix, Prime Video e AirPin, se instalados.
- Apps de pacotes diferentes com o mesmo nome (ex.: dois "Configurações") aparecem uma vez só.
- Atalhos do projetor: Keystone, Projeção, HDMI, Wi-Fi, Bluetooth, Ajustes e Limpar RAM.
- Cabeçalho com rede, RAM livre e temperatura do SoC.
- Libera processos em segundo plano de outros apps antes de abrir um (o aparelho tem pouca RAM).
- Não pede INTERNET. Só `ACCESS_NETWORK_STATE` e `KILL_BACKGROUND_PROCESSES`.
- Atende `HOME` **e** `HOME.JM`, a categoria que a tecla HOME deste firmware usa.
  Por isso substitui também o `com.jm.jmlauncher`.

> Testado em um único aparelho. Em outro lote do mesmo projetor o firmware pode ser diferente.

## Requisitos

- Windows, macOS ou Linux com `bash`, JDK 17 e `adb`.
- Android SDK com `build-tools/34.0.0` e `platforms/android-33` em `~/AndroidSdk`
  (ou defina `ANDROID_SDK`).
- ADB habilitado no projetor (Wi-Fi ou USB).

## Compilar

```bash
./build.sh          # gera build/hyhome.apk (launcher); ./build.sh phone gera o app do celular
```

O script usa `aapt2`, `javac`, `d8` e `apksigner`, sem Gradle. Na primeira vez cria um keystore de
**depuração** em `~/.hyhome-debug.jks` (senha `hyhome123`). Para distribuir o APK, crie o seu e
aponte `HYHOME_KEYSTORE`; troque também as senhas em `build.sh`.

## Instalar e ativar

```bash
adb connect 192.168.0.15:5555
scripts/install.sh 192.168.0.15:5555
```

O script instala o APK, define o HY Home como HOME, desativa o `com.jm.jmlauncher` e abre a tela
inicial. Nada é apagado: tudo é reversível.

### Desfazer

```bash
scripts/install.sh 192.168.0.15:5555 --rollback
```

Reativa o JmLauncher, devolve o papel de HOME ao FLauncher (se estiver instalado) e remove o
HY Home. Sem FLauncher, ajuste o HOME manualmente depois.

## Uso

| Ação | Como |
|---|---|
| Abrir app | OK |
| Favoritar, ocultar, ver informações | Segurar OK sobre o app |
| Voltar ao topo | Tecla HOME |
| Mostrar ocultos | Botão "Mostrar ocultos (N)" no fim da lista |

## Controle remoto pelo PC (sem cabo)

`tools/remote/hy300_remote.py` controla o projetor pelo Wi-Fi, com o mesmo visual do HY Home. Só
precisa de Python 3 (com tkinter) e do `adb` no PATH. Nenhum app extra no projetor.

```bash
python tools/remote/hy300_remote.py 192.168.0.15:5555
```

- D-pad circular, voltar, início, menu, volume e mudo. Segurar uma seta ou o volume repete.
- Atalhos de apps (SmartTube, Netflix, MX Player, Keystone, TV Bro, HY Home).
- Digitar texto, captura de tela (salva em `Pictures\HY300`), limpar RAM, espelhar, reiniciar e
  manter a janela no topo (botão "Mais").
- Mostra a conexão (verde, âmbar ou vermelho), a RAM livre e a temperatura (âmbar a partir de 80 °C).
- Teclado: setas, Enter (OK), Esc/Backspace (voltar), Home, PageUp/PageDown (volume), Espaço (play).
- Teclas seguidas vão em uma única chamada ao `adb`, em ordem. Se o projetor sumir, reconecta com
  espera crescente. Os erros dizem a causa: `adb` ausente, chave não aceita ou aparelho offline.
- Opcional: `hy300_remote.json` ao lado do script, com `{"device": "ip:porta", "apps": [["Nome",
  "pacote ou pacote/atividade", "#cor", "letra"]]}`.
- "Digitar texto" aceita só ASCII, limite do `input text` do Android.
- "Espelhar" usa o [scrcpy](https://github.com/Genymobile/scrcpy), opcional e instalado à parte.
- Segurança: o ADB sobre Wi-Fi dá controle total. Mantenha `ro.adb.secure=1` e use só em rede confiável.

## HY Remote: controle pelo celular (Android)

App em `phone/` que controla o projetor pelo Wi-Fi, sem cabo e **sem instalar nada no projetor**.
Ele fala o protocolo ADB direto com o `adbd` do projetor (porta 5555), com a mesma autenticação RSA
do `adb` do PC. Nenhum serviço novo escuta no projetor.

```bash
./build.sh phone        # gera build/hyremote.apk (29 KB, Android 8.0 ou mais novo)
```

1. Passe o APK para o celular e instale (permita "fontes desconhecidas" para o app que o abrir).
2. Celular e projetor na mesma rede Wi-Fi. A depuração por Wi-Fi do projetor (ADB, porta 5555) precisa
   estar ligada.
3. Abra o HY Remote, informe o IP do projetor e toque em **Conectar**.
4. Na primeira vez o projetor mostra "Permitir depuração USB?". Marque **Sempre permitir** e confirme.
   Se o pedido não aparecer, use o menu **⋯ > Ver minha chave ADB** e acrescente a linha a
   `/data/misc/adb/adb_keys` pelo `adb` do PC (como root).

Tem D-pad com repetição ao segurar, voltar/início/menu, volume e mudo, atalhos de apps, digitar texto
(só ASCII), RAM e temperatura. Os botões de volume do celular controlam o volume do projetor.
Para o projetor não mudar de IP, reserve o endereço no roteador.

Segurança: quem tiver o celular desbloqueado com o app controla o projetor, e a chave privada fica no
armazenamento privado do app. Revogue em *Opções do desenvolvedor > Revogar autorizações de
depuração USB* no projetor.

Teste do protocolo no PC (usa a chave do `adb` do PC, sem pedir autorização nova):

```bash
phone/test/run.sh 192.168.0.15:5555
```

## Reaplicar a fase 1 depois de um reset

As mudanças de `/system` (scripts do fabricante neutralizados, `hosts`, `ro.adb.secure=1`) ficam no
overlay do aparelho, dentro do `userdata`. Um reset de fábrica as apaga. Para refazer e conferir:

```bash
scripts/phase1/verify.sh                 # SÓ LEITURA: confere o estado atual (exit 0 = tudo certo)
scripts/phase1/reapply.sh                # simulação: mostra o que faria, não altera nada
scripts/phase1/reapply.sh --apply        # aplica; depois reinicie o projetor e rode verify.sh
scripts/phase1/selftest.sh <pasta>       # offline: confere as transformações com os arquivos originais
```

O `reapply.sh` confere tudo em leitura antes de gravar e **aborta sem mudar nada** se o aparelho não for
o HY300-T, se um arquivo não tiver o SHA-256 do original (firmware diferente) ou se a chave ADB do PC
(`~/.android/adbkey.pub`) não existir, porque sem ela `ro.adb.secure=1` trancaria você fora.
Ele **não** reaplica os arquivos de `vendor`/`product` do overlay, que são de 18/04/2026 e vieram da
personalização remota do fabricante.

## Documentação

- [Guia de engenharia reversa](docs/GUIA-ENGENHARIA-REVERSA.md): como analisar e melhorar o seu próprio
  projetor Android, do acesso por ADB ao launcher, com os cuidados e o que não fazer.
- [Relatório de engenharia reversa](docs/ENGENHARIA-REVERSA.md): o que encontramos no HY300-T.

## Estrutura

```
AndroidManifest.xml
res/                      ícones e tema
src/com/paulo/hyhome/     MainActivity (tela), AppStore (lista), Shortcuts (atalhos do projetor),
                          IconLoader, Prefs, Tile, Ui
build.sh                  compilação sem Gradle
scripts/install.sh        instalação e rollback via ADB
scripts/phase1/           reaplicar e verificar a fase 1 (reapply, verify, selftest)
tools/remote/             controle remoto do projetor pelo PC (Python/tkinter)
phone/                    app HY Remote para o celular (cliente ADB) e teste de protocolo
docs/                     relatório (resultados) e guia (passo a passo) de engenharia reversa
```

## Limites conhecidos

- O atalho **Keystone** só abre o app `com.softwinner.tcorrection`. Escrever os cantos direto em
  `persist.vendor.hwc.keystone` não teve efeito observável no compositor (ver o guia, seção 6).
- A temperatura é lida de `/sys/class/thermal/thermal_zone0/temp`. Se o caminho não existir em
  outro firmware, o valor simplesmente não aparece.
- As mudanças feitas em `/system` descritas no guia ficam no overlay do aparelho e **não** estão
  gravadas na partição `super`.

## Licença

MIT. Veja o arquivo [LICENSE](LICENSE).
