package com.madadiaspora.chekeov2

object SecureConfig {
    fun apiKey(): String = BuildConfig.DEEPSEEK_API_KEY.trim()
}
