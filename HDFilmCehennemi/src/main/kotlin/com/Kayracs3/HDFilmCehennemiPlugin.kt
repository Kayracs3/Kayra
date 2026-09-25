package com.Kayracs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Editable plugin wrapper reconstructed from classes.dex.
 * The installed DEX contains a Plugin subclass whose load(Context) registers
 * the HDFilmCehennemi MainAPI implementation.
 */
class HDFilmCehennemiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmCehennemi())
    }
}
