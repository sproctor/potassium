package com.seanproctor.potassium.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The value written to `resources/updater-cache-dir` must match electron-builder's
 * `AppInfo.updaterCacheDirName` (`sanitizeFileName(name).toLowerCase() + "-updater"`,
 * where `name` is the package.json name this plugin generates) — the NSIS installer
 * copies itself to `%LOCALAPPDATA%\<that name>\installer.exe` at install time.
 */
class UpdaterCacheDirNameTest {
    @Test
    fun `derives the electron-builder updater cache dir name`() {
        assertEquals("myapp-updater", AbstractElectronBuilderPackageTask.updaterCacheDirName("myapp"))
        assertEquals("my-app-updater", AbstractElectronBuilderPackageTask.updaterCacheDirName("My App"))
        assertEquals("my.app_2-updater", AbstractElectronBuilderPackageTask.updaterCacheDirName("My.App_2"))
    }

    @Test
    fun `npm package name is lowercase and filename-safe`() {
        assertEquals("my-app", AbstractElectronBuilderPackageTask.npmPackageName("My App"))
        assertEquals("app", AbstractElectronBuilderPackageTask.npmPackageName("---"))
        // Each disallowed character maps to one dash (no collapsing) — matches package.json.
        assertEquals("caf--tool", AbstractElectronBuilderPackageTask.npmPackageName("Café Tool"))
    }
}
