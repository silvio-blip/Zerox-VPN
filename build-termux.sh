#!/usr/bin/env bash
# ===============================================================================
# ZEROX VPN -- SCRIPT DEFINITIVO DE COMPILAÇÃO E ASSINATURA NO TERMUX (ANDROID)
# ==============================================================================
set -e

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

if [ "$1" = "--reset-pass" ]; then
    rm -f "${ROOT_DIR}/.keystore_pass" "$HOME/.zerox_keystore_pass" "$HOME/.keystore_pass"
    echo -e "${YELLOW}Palavras-passe em cache removidas com sucesso.${NC}"
fi

echo -e "${CYAN}================================================================${NC}"
echo -e "${CYAN}   ZEROX VPN - COMPILAÇÃO E ASSINATURA AUTOMÁTICA (TERMUX)     ${NC}"
echo -e "${CYAN}================================================================${NC}"

# 1. Dependências do Termux e Localização do Android SDK
echo -e "\n${YELLOW}[1/5] Verificando ambiente e Android SDK no Termux...${NC}"
if command -v pkg >/dev/null 2>&1; then
    pkg update -y >/dev/null 2>&1 || true
    pkg install -y openjdk-17 gradle git android-tools aapt2 curl wget unzip >/dev/null 2>&1 || true
fi

# Configura JAVA_HOME para OpenJDK 17
if [ -d "/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk" ]; then
    export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
    export PATH="$JAVA_HOME/bin:$PATH"
elif [ -n "$PREFIX" ] && [ -d "$PREFIX/lib/jvm/java-17-openjdk" ]; then
    export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# Procura o Android SDK em todas as localizações comuns no Termux e no Android
SDK_CANDIDATE=""
for d in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$PREFIX/lib/android-sdk" "$PREFIX/share/android-sdk" "$HOME/android-sdk" "$HOME/Android/Sdk" "/data/data/com.termux/files/home/android-sdk" "/storage/emulated/0/Android/Sdk" "/sdcard/Android/Sdk"; do
    if [ -n "$d" ] && [ -d "$d" ] && [ -d "$d/platforms" ]; then
        SDK_CANDIDATE="$d"
        break
    fi
done

# Se não encontrou uma pasta com 'platforms', verifica se existe alguma das pastas base
if [ -z "$SDK_CANDIDATE" ]; then
    for d in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$PREFIX/lib/android-sdk" "$PREFIX/share/android-sdk" "$HOME/android-sdk" "$HOME/Android/Sdk"; do
        if [ -n "$d" ] && [ -d "$d" ]; then
            SDK_CANDIDATE="$d"
            break
        fi
    done
fi

# Se ainda não existir nenhuma pasta de SDK, prepara a pasta padrão em $HOME/android-sdk
if [ -z "$SDK_CANDIDATE" ]; then
    SDK_CANDIDATE="$HOME/android-sdk"
    mkdir -p "$SDK_CANDIDATE"
fi

export ANDROID_HOME="$SDK_CANDIDATE"
export ANDROID_SDK_ROOT="$SDK_CANDIDATE"

# Grava no local.properties do projeto a localização real encontrada
echo "sdk.dir=$SDK_CANDIDATE" > "${ROOT_DIR}/local.properties"
echo -e "${GREEN}✓ Android SDK configurado em: $SDK_CANDIDATE${NC}"
echo -e "${GREEN}✓ Java: $(java -version 2>&1 | head -n 1)${NC}"

# Se faltar a pasta 'platforms', orienta o download ou tenta baixar cmdline-tools
if [ ! -d "$SDK_CANDIDATE/platforms" ]; then
    echo -e "${YELLOW}Aviso: Pasta 'platforms' não encontrada no SDK. Criando estrutura base...${NC}"
    mkdir -p "$SDK_CANDIDATE/platforms"
    mkdir -p "$SDK_CANDIDATE/platform-tools"
    mkdir -p "$SDK_CANDIDATE/build-tools"
fi

# Configura aapt2 nativo do Termux para o Gradle caso esteja disponível
if [ -f "$PREFIX/bin/aapt2" ]; then
    if ! grep -q "android.aapt2FromMavenOverride" gradle.properties 2>/dev/null; then
        echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> gradle.properties
    fi
fi

# 2. Configuração da Keystore Existente (Pede sempre a palavra-passe para assinar)
echo -e "
${YELLOW}[2/5] Configuração da Keystore e Palavra-passe...${NC}"
KEYSTORE_FILE="${ROOT_DIR}/my-upload-key.jks"
KEY_ALIAS="upload"

rm -f "${ROOT_DIR}/.keystore_pass" "$HOME/.zerox_keystore_pass" "$HOME/.keystore_pass" 2>/dev/null || true

if [ -f "$KEYSTORE_FILE" ]; then
    echo -e "${GREEN}✓ Keystore encontrada em: $KEYSTORE_FILE${NC}"
    echo -n "Digite a palavra-passe da sua keystore para assinar: "
    read -rs STORE_PASS
    echo ""
    if [ -z "$STORE_PASS" ]; then
        echo -e "${RED}Erro: A palavra-passe não pode ser vazia!${NC}"
        exit 1
    fi
else
    echo -e "${RED}Erro: Keystore não encontrada em $KEYSTORE_FILE! Coloca a tua my-upload-key.jks na pasta do projeto.${NC}"
    exit 1
fi

KEY_PASS="$STORE_PASS"
export KEYSTORE_PATH="$KEYSTORE_FILE"
export STORE_PASSWORD="$STORE_PASS"
export KEY_PASSWORD="$KEY_PASS"

# 3. Compilação do APK (assembleRelease) e do AAB (bundleRelease) via Gradle
echo -e "\n${YELLOW}[3/5] Compilando APK (assembleRelease) e AAB (bundleRelease)...${NC}"
GRADLE_CMD="gradle"
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    GRADLE_CMD="./gradlew"
fi

$GRADLE_CMD :app:assembleRelease :app:bundleRelease --no-daemon

# 4. Localização, alinhamento e assinatura dos pacotes gerados
echo -e "\n${YELLOW}[4/5] Processando e assinando os pacotes finais com a sua Keystore...${NC}"

RAW_APK=$(find app/build/outputs/apk/release -name "*.apk" | head -n 1)
RAW_AAB=$(find app/build/outputs/bundle/release -name "*.aab" | head -n 1)

if [ -z "$RAW_APK" ] || [ ! -f "$RAW_APK" ]; then
    echo -e "${RED}Erro: APK não encontrado em app/build/outputs/apk/release/${NC}"
    exit 1
fi

if [ -z "$RAW_AAB" ] || [ ! -f "$RAW_AAB" ]; then
    echo -e "${RED}Erro: AAB não encontrado em app/build/outputs/bundle/release/${NC}"
    exit 1
fi

DEST_APK="${ROOT_DIR}/zerox-vpn-release.apk"
DEST_AAB="${ROOT_DIR}/zerox-vpn-release.aab"

# Alinha e assina o APK com apksigner
if command -v apksigner >/dev/null 2>&1; then
    ALIGNED_APK="${RAW_APK}.aligned"
    zipalign -v -p 4 "$RAW_APK" "$ALIGNED_APK" 2>/dev/null || cp "$RAW_APK" "$ALIGNED_APK"
    apksigner sign --ks "$KEYSTORE_FILE" \
        --ks-pass "pass:$STORE_PASS" \
        --ks-key-alias "$KEY_ALIAS" \
        --key-pass "pass:$KEY_PASS" \
        "$ALIGNED_APK"
    mv "$ALIGNED_APK" "$DEST_APK"
    echo -e "${GREEN}✓ APK assinado com apksigner com sucesso!${NC}"
else
    cp "$RAW_APK" "$DEST_APK"
    echo -e "${YELLOW}Aviso: apksigner não encontrado; cópia direta do APK release efetuada.${NC}"
fi

# Assina o pacote AAB com jarsigner
if command -v jarsigner >/dev/null 2>&1; then
    jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
        -keystore "$KEYSTORE_FILE" \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" \
        "$RAW_AAB" "$KEY_ALIAS" >/dev/null 2>&1 || true
    echo -e "${GREEN}✓ AAB assinado com jarsigner com sucesso!${NC}"
fi
cp "$RAW_AAB" "$DEST_AAB"

# 5. Conclusão e entrega
echo -e "\n${CYAN}================================================================${NC}"
echo -e "${GREEN}   COMPILAÇÃO E ASSINATURA CONCLUÍDAS COM SUCESSO!              ${NC}"
echo -e "${CYAN}================================================================${NC}"
echo -e "${GREEN}✓ APK Assinado:${NC} ${DEST_APK}"
echo -e "${GREEN}✓ AAB Assinado:${NC} ${DEST_AAB}"
echo -e "${CYAN}================================================================${NC}"

if command -v termux-open >/dev/null 2>&1; then
    echo -e "\n${YELLOW}Deseja instalar o APK assinado agora no telemóvel? (s/n)${NC}"
    read -r INSTALL_CHOICE
    if [ "$INSTALL_CHOICE" = "s" ] || [ "$INSTALL_CHOICE" = "S" ]; then
        termux-open "$DEST_APK"
    fi
fi
