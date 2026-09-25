package com.Kayracs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin


class HDFilmCehennemiEntry : Plugin() {
    private val delegate = HDFilmCehennemiPlugin()

    override fun load(context: Context) {
        delegate.load(context)
    }

    override fun beforeUnload() {
        delegate.beforeUnload()
    }
}
