/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat

class AssistActivity : AppCompatActivity() {
    private lateinit var fragment: FileListFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        NightModeHelper.apply(this)
        
        val themeRes = if (Settings.MATERIAL_DESIGN_3.valueCompat) {
            R.style.Theme_MaterialFiles_AssistDialog_Material3
        } else {
            R.style.Theme_MaterialFiles_AssistDialog
        }
        setTheme(themeRes)

        super.onCreate(savedInstanceState)

        val window = window
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )

        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val argsIntent = intent.cloneFilter()
            fragment = FileListFragment().putArgs(FileListFragment.Args(argsIntent))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as FileListFragment
        }
    }
}
