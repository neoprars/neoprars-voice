package com.neoprars.voice.engine

import io.ktor.client.HttpClient

/** Cliente HTTP con el motor nativo de cada plataforma (OkHttp en Android/JVM, Darwin en iOS). */
expect fun createHttpClient(): HttpClient
