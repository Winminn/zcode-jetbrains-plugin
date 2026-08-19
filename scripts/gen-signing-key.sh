#!/usr/bin/env bash
# 生成 JetBrains Marketplace 插件签名密钥对（RSA 4096 私钥 + 自签名证书链）
#
# 用法:
#   ./scripts/gen-signing-key.sh [私钥密码]
#   不带参数则生成无加密私钥（CI 自动化最简单）；带参数则私钥用 AES-256-CBC 加密。
#
# 产物输出到 ~/.zcode/plugin-signing/（项目外，不入库），可通过环境变量
# ZC_PLUGIN_SIGNING_DIR 覆盖输出目录。重复运行会拒绝覆盖已有密钥。
#
# 注意：Windows Git Bash 下 mingw64 版 openssl 对 /c/... 形式路径处理不一致，
# 所以统一 cd 到输出目录后用相对文件名操作（也规避了 MSYS 对 /CN= 的路径转换，
# 仅 subj 参数需 MSYS_NO_PATHCONV=1）。
set -euo pipefail

OUT_DIR="${ZC_PLUGIN_SIGNING_DIR:-$HOME/.zcode/plugin-signing}"
PASSWORD="${1:-}"
PRIVATE_KEY="private.pem"
CERT_CHAIN="chain.crt"

if [[ -f "$OUT_DIR/$PRIVATE_KEY" || -f "$OUT_DIR/$CERT_CHAIN" ]]; then
    echo "错误: $OUT_DIR 下已存在密钥文件，为避免覆盖已中止。"
    echo "如确需重新生成，请先手动删除该目录。"
    exit 1
fi

mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

if [[ -n "$PASSWORD" ]]; then
    openssl genpkey -aes-256-cbc -algorithm RSA -pkeyopt rsa_keygen_bits:4096 \
        -pass "pass:$PASSWORD" -out "$PRIVATE_KEY"
else
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$PRIVATE_KEY"
fi

# 自签名证书：subj 仅作标识，Marketplace 侧信任链与作者侧证书相互独立
MSYS_NO_PATHCONV=1 openssl req -new -x509 -key "$PRIVATE_KEY" \
    ${PASSWORD:+-passin "pass:$PASSWORD"} \
    -out "$CERT_CHAIN" -days 3650 -subj "/CN=ZC GUI Plugin Signing"

chmod 600 "$PRIVATE_KEY"

echo "已生成:"
echo "  私钥: $OUT_DIR/$PRIVATE_KEY"
echo "  证书: $OUT_DIR/$CERT_CHAIN"
echo ""
echo "本地发布前导出以下环境变量（gradle-intellij-plugin 从环境变量读取）:"
echo "  export CERTIFICATE_CHAIN=\"\$(cat $OUT_DIR/$CERT_CHAIN)\""
echo "  export PRIVATE_KEY=\"\$(cat $OUT_DIR/$PRIVATE_KEY)\""
echo "  export PRIVATE_KEY_PASSWORD=\"$PASSWORD\""
echo ""
echo "CI 发布（release.yml）时把这三个值配成 GitHub 仓库 Secrets:"
echo "  MARKETPLACE_TOKEN / CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD"
