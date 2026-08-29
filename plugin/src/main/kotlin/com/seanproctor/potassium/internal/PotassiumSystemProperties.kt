/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

internal const val APP_RESOURCES_DIR = "compose.application.resources.dir"
internal const val SKIKO_LIBRARY_PATH = "skiko.library.path"
internal const val CONFIGURE_SWING_GLOBALS = "compose.application.configure.swing.globals"
internal const val APP_ID = "app.id"
internal const val APP_VERSION = "app.version"

// Read by the JetBrains Runtime's Wayland toolkit as the window app_id; without it the toolkit
// falls back to sun.java.command (the dotted main class plus any CLI arguments), which may not
// match the installed .desktop file, so Wayland desktops show a generic icon for the window.
internal const val AWT_APP_ID = "awt.app.id"
