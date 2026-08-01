/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

import com.seanproctor.potassium.internal.utils.toBooleanProvider
import com.seanproctor.potassium.internal.utils.valueOrNull
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

internal object PotassiumProperties {
    internal const val VERBOSE = "potassium.verbose"
    internal const val PRESERVE_WD = "potassium.preserve.working.dir"
    internal const val MAC_SIGN = "potassium.mac.sign"
    internal const val MAC_SIGN_ID = "potassium.mac.signing.identity"
    internal const val MAC_SIGN_KEYCHAIN = "potassium.mac.signing.keychain"
    internal const val MAC_SIGN_PREFIX = "potassium.mac.signing.prefix"
    internal const val MAC_NOTARIZATION_APPLE_ID = "potassium.mac.notarization.appleID"
    internal const val MAC_NOTARIZATION_PASSWORD = "potassium.mac.notarization.password"
    internal const val MAC_NOTARIZATION_TEAM_ID_PROVIDER = "potassium.mac.notarization.teamID"
    internal const val MAC_NOTARIZATION_KEYCHAIN_PROFILE = "potassium.mac.notarization.keychainProfile"
    internal const val MAC_NOTARIZATION_KEYCHAIN_PATH = "potassium.mac.notarization.keychainPath"
    internal const val MAC_NOTARIZATION_API_KEY = "potassium.mac.notarization.apiKey"
    internal const val MAC_NOTARIZATION_API_KEY_ID = "potassium.mac.notarization.apiKeyId"
    internal const val MAC_NOTARIZATION_API_ISSUER = "potassium.mac.notarization.apiIssuer"
    internal const val CHECK_JDK_VENDOR = "potassium.packaging.checkJdkVendor"
    internal const val ELECTRON_BUILDER_NODE_PATH = "potassium.electronBuilder.nodePath"
    internal const val ELECTRON_BUILDER_PUBLISH_MODE = "potassium.electronBuilder.publishMode"

    fun isVerbose(providers: ProviderFactory): Provider<Boolean> =
        providers.valueOrNull(VERBOSE).toBooleanProvider(false)

    fun preserveWorkingDir(providers: ProviderFactory): Provider<Boolean> =
        providers.valueOrNull(PRESERVE_WD).toBooleanProvider(false)

    fun macSign(providers: ProviderFactory): Provider<Boolean> =
        providers.valueOrNull(MAC_SIGN).toBooleanProvider(false)

    fun macSignIdentity(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_SIGN_ID)

    fun macSignKeychain(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_SIGN_KEYCHAIN)

    fun macSignPrefix(providers: ProviderFactory): Provider<String> = providers.valueOrNull(MAC_SIGN_PREFIX)

    @Suppress("MaxLineLength")
    fun macNotarizationAppleID(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_APPLE_ID)

    @Suppress("MaxLineLength")
    fun macNotarizationPassword(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_PASSWORD)

    @Suppress("MaxLineLength")
    fun macNotarizationTeamID(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_TEAM_ID_PROVIDER)

    @Suppress("MaxLineLength")
    fun macNotarizationKeychainProfile(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_KEYCHAIN_PROFILE)

    @Suppress("MaxLineLength")
    fun macNotarizationKeychainPath(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_KEYCHAIN_PATH)

    @Suppress("MaxLineLength")
    fun macNotarizationApiKey(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_API_KEY)

    @Suppress("MaxLineLength")
    fun macNotarizationApiKeyId(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_API_KEY_ID)

    @Suppress("MaxLineLength")
    fun macNotarizationApiIssuer(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(MAC_NOTARIZATION_API_ISSUER)

    fun checkJdkVendor(providers: ProviderFactory): Provider<Boolean> =
        providers.valueOrNull(CHECK_JDK_VENDOR).toBooleanProvider(true)

    @Suppress("MaxLineLength")
    fun electronBuilderNodePath(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(ELECTRON_BUILDER_NODE_PATH)

    @Suppress("MaxLineLength")
    fun electronBuilderPublishMode(providers: ProviderFactory): Provider<String> =
        providers.valueOrNull(ELECTRON_BUILDER_PUBLISH_MODE)
}
