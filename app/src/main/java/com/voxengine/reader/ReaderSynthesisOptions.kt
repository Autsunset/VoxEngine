package com.voxengine.reader

data class ReaderSynthesisOptions(
    val contextEnabled: Boolean = true,
    val bufferPages: Int = 2,
    val chunkChars: Int = 600,
    val cacheChapters: Int = 20
) {
    fun bounded() = copy(bufferPages = bufferPages.coerceIn(1, 20), chunkChars = chunkChars.coerceIn(180, 1800), cacheChapters = cacheChapters.coerceIn(1, 100))

    companion object {
        fun parse(json: String?) = runCatching {
            com.google.gson.Gson().fromJson(json, ReaderSynthesisOptions::class.java)?.bounded()
        }.getOrNull() ?: ReaderSynthesisOptions()
    }
}
