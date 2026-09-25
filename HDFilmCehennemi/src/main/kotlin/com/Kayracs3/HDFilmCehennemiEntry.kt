package com.Kayracs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Thin editable entry point for Kayra builds.
 *
 * The supplied DEX also contained a Wio/TurkSinema-specific entry wrapper that
 * delegates to HDFilmCehennemiPlugin and performs provider-list/UI bookkeeping.
 * That surrounding app integration is intentionally kept out of the provider
 * source so the provider can be copied into the Kayra repository cleanly.
 */
class HDFilmCehennemiEntry : Plugin() {
    private val delegate = HDFilmCehennemiPlugin()

    override fun load(context: Context) {
        delegate.load(context)
    }

    override fun beforeUnload() {
        delegate.beforeUnload()
    }
}
