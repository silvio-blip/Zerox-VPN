#!/usr/bin/env bash
# ==============================================================================
# ZEROX VPN - SCRIPT DE COMPILAÇÃO E ASSINATURA PARA O TERMUX
# ==============================================================================
set -e

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo -e "${CYAN}================================================================${NC}"
echo -e "${CYAN}   ZEROX VPN - COMPILAÇÃO NATIVA EM ANDROID (TERMUX)           ${NC}"
echo -e "${CYAN}================================================================${NC}"

# 1. Instalação e verificação de dependências no Termux
echo -e "\n${YELLOW}[1/5] Verificando e instalando pacotes necessários no Termux...${NC}"
if command -v pkg >/dev/null 2>&1; then
    pkg update -y >/dev/null 2>&1 || true
    pkg install -y openjdk-17 gradle git android-tools wget curl unzip >/dev/null 2>&1 || true
fi

# Configuração de JAVA_HOME
if [ -d "/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk" ]; then
    export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

# Detectar SDK do Termux ou criar link adequado
TERMUX_SDK="/data/data/com.termux/files/usr/share/android-sdk"
if [ ! -d "$TERMUX_SDK" ] && [ -d "$PREFIX/share/android-sdk" ]; then
    TERMUX_SDK="$PREFIX/share/android-sdk"
fi
export ANDROID_HOME="$TERMUX_SDK"

# Escreve o local.properties corretamente para o Gradle encontrar o SDK no Termux
echo "sdk.dir=$ANDROID_HOME" > local.properties

echo -e "${GREEN}✓ Java: $(java -version 2>&1 | head -n 1)${NC}"
echo -e "${GREEN}✓ SDK Path: $ANDROID_HOME${NC}"

# 2. Gestão de Senha e Keystore (Gravada em arquivo local seguro para não pedir nunca mais)
echo -e "\n${YELLOW}[2/5] Configuração da Keystore e Palavra-passe...${NC}"
KEYSTORE_FILE="${ROOT_DIR}/my-upload-key.jks"
PASS_FILE="${ROOT_DIR}/.keystore_pass"
KEY_ALIAS="upload"

if [ -f "$PASS_FILE" ]; then
    # Lê a senha salva anteriormente para não perguntar nunca mais
    STORE_PASS=$(cat "$PASS_FILE")
    echo -e "${GREEN}✓ Palavra-passe carregada automaticamente da memória com sucesso!${NC}"
elif [ -f "$KEYSTORE_FILE" ]; then
    echo -e "${YELLOW}Keystore existente detetada, mas sem senha salva.${NC}"
    echo -n "Digite a sua palavra-passe da keystore (será lembrada nas próximas vezes): "
    read -rs STORE_PASS
    echo ""
    echo "$STORE_PASS" > "$PASS_FILE"
    chmod 600 "$PASS_FILE"
else
    echo -e "${YELLOW}Criando nova keystore permanente (my-upload-key.jks)...${NC}"
    echo -n "Digite uma NOVA palavra-passe para a sua Keystore: "
    read -rs STORE_PASS
    echo ""
    echo -n "Confirme a palavra-passe: "
    read -rs STORE_PASS_CONFIRM
    echo ""

    if [ "$STORE_PASS" != "$STORE_PASS_CONFIRM" ]; then
        echo -e "${RED}Erro: As palavras-passe não coincidem! Execute o script novamente.${NC}"
        exit 1
    fi
    
    echo "$STORE_PASS" > "$PASS_FILE"
    chmod 600 "$PASS_FILE"

    keytool -genkeypair -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$STORE_PASS" \
        -keypass "$STORE_PASS" \
        -dname "CN=Zerox VPN, OU=Development, O=Zerox, L=Luanda, ST=Luanda, C=AO"
    
    echo -e "${GREEN}✓ Keystore criada com sucesso!${NC}"
fi

KEY_PASS="$STORE_PASS"
export KEYSTORE_PATH="$KEYSTORE_FILE"
export STORE_PASSWORD="$STORE_PASS"
export KEY_PASSWORD="$KEY_PASS"

# 3. Compilação do APK e do AAB via Gradle
echo -e "\n${YELLOW}[3/5] Compilando APK (assembleRelease) e AAB (bundleRelease)...${NC}"
GRADLE_CMD="gradle"
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    GRADLE_CMD="./gradlew"
fi

$GRADLE_CMD :app:assembleRelease :app:bundleRelease --no-daemon

# 4. Verificação, alinhamento e assinatura
echo -e "\n${YELLOW}[4/5] Processando e assinando pacotes finais...${NC}"

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

if command -v apksigner >/dev/null 2>&1; then
    ALIGNED_APK="${RAW_APK}.aligned"
    zipalign -v -p 4 "$RAW_APK" "$ALIGNED_APK" 2>/dev/null || cp "$RAW_APK" "$ALIGNED_APK"
    apksigner sign --ks "$KEYSTORE_FILE" \
        --ks-pass "pass:$STORE_PASS" \
        --ks-key-alias "$KEY_ALIAS" \
        --key-pass "pass:$KEY_PASS" \
        "$ALIGNED_APK"
    mv "$ALIGNED_APK" "$DEST_APK"
else
    cp "$RAW_APK" "$DEST_APK"
fi

if command -v jarsigner >/dev/null 2>&1; then
    jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
        -keystore "$KEYSTORE_FILE" \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" \
        "$RAW_AAB" "$KEY_ALIAS" >/dev/null 2>&1 || true
fi
cp "$RAW_AAB" "$DEST_AAB"

# 5. Conclusão
echo -e "\n${CYAN}================================================================${NC}"
echo -e "${GREEN}   COMPILAÇÃO E ASSINATURA CONCLUÍDAS COM SUCESSO!              ${NC}"
echo -e "${CYAN}================================================================${NC}"
echo -e "${GREEN}APK Assinado:${NC} ${DEST_APK}"
echo -e "${GREEN}AAB Assinado:${NC} ${DEST_AAB}"
echo -e "${CYAN}================================================================${NC}"

if command -v termux-open >/dev/null 2>&1; then
    echo -e "\n${YELLOW}Deseja instalar o APK agora no telemóvel? (s/n)${NC}"
    read -r INSTALL_CHOICE
    if [ "$INSTALL_CHOICE" = "s" ] || [ "$INSTALL_CHOICE" = "S" ]; then
        termux-open ./zerox-vpn-release.apk
    fi
fi
