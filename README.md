# HY Home

Launcher leve, sem rede e sem anúncios para o projetor **HY300-T** (Amlogic T950S, Android 13,
tela 800×480, ~1 GB de RAM). Feito para controle remoto (D-pad).

- Grade de apps com favoritos e apps ocultos (segure OK sobre um app).
- Atalhos do projetor: Keystone, Projeção, HDMI, Wi-Fi, Bluetooth, Ajustes.
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
./build.sh          # gera build/hyhome.apk
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

## Estrutura

```
AndroidManifest.xml
res/                      ícones e tema
src/com/paulo/hyhome/     MainActivity (tela), AppStore (lista), Shortcuts (atalhos do projetor),
                          IconLoader, Prefs, Tile, Ui
build.sh                  compilação sem Gradle
scripts/install.sh        instalação e rollback via ADB
docs/                     guia de engenharia reversa do HY300-T
```

## Limites conhecidos

- O atalho **Keystone** só abre o app `com.softwinner.tcorrection`. Escrever os cantos direto em
  `persist.vendor.hwc.keystone` não teve efeito observável no compositor (ver o guia, seção 6).
- A temperatura é lida de `/sys/class/thermal/thermal_zone0/temp`. Se o caminho não existir em
  outro firmware, o valor simplesmente não aparece.
- As mudanças feitas em `/system` descritas no guia ficam no overlay do aparelho e **não** estão
  gravadas na partição `super`.

## Licença

Defina antes de publicar (por exemplo MIT). Enquanto não houver arquivo `LICENSE`, todos os
direitos ficam reservados ao autor.
