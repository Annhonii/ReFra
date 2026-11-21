/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.telegram.di

import android.content.Context
import com.dot.gallery.cloud.core.MediaCapabilityProvider
import com.dot.gallery.cloud.core.ProviderInstanceFactory
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.netfs.NetworkFileSystemProvider
import com.dot.gallery.cloud.telegram.TelegramBackend
import com.dot.gallery.core.injection.Singleton
import com.dot.gallery.data.dao.CloudMediaDao
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject contribution surface for the Telegram private cloud. The `notelegram` variant of this file
 * declares an empty interface so the component compiles when the flavour is off.
 */
interface TelegramProviderComponent {

    @Singleton
    @IntoSet
    @Provides
    fun provideTelegramProviderFactory(
        context: Context,
        cloudMediaDao: CloudMediaDao
    ): ProviderInstanceFactory = object : ProviderInstanceFactory {
        override val providerType = ProviderType.TELEGRAM
        override fun create(): MediaCapabilityProvider =
            NetworkFileSystemProvider(context, cloudMediaDao, TelegramBackend(context))
    }
}
