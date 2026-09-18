package com.mit.reader

import android.app.Application
import com.mit.reader.data.LibraryRepository
import com.mit.reader.data.ServerConfig
import com.mit.reader.data.TranslationApi

class ReaderApp : Application() {
    lateinit var library: LibraryRepository
        private set
    val api = TranslationApi()

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(this)
        library = LibraryRepository(this)
    }
}
