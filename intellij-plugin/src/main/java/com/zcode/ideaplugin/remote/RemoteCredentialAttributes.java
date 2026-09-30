package com.zcode.ideaplugin.remote;

import com.intellij.credentialStore.CredentialAttributes;

/**
 * 经 Java 调用 {@link CredentialAttributes} 的 (String) 重载。Kotlin 调用点会把单参构造
 * 解析为主构造默认参数的 DefaultConstructorMarker 桥（该桥随 requestor 构造族在 253+ 平台
 * 标 @Deprecated，Marketplace verifier 红标）；Java 无默认参数语义，精确命中各版本均
 * 无标注的真实重载。
 */
final class RemoteCredentialAttributes {

    private RemoteCredentialAttributes() {
    }

    static CredentialAttributes forService(String serviceName) {
        return new CredentialAttributes(serviceName);
    }
}
