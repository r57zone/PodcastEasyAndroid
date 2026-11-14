package com.r57zone.podcasteasy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Доступ к файлам: на Android 11+ — «Доступ ко всем файлам», ниже — обычное разрешение на чтение/запись. */
object Access {
    // Константа Manifest.permission.POST_NOTIFICATIONS есть только в compileSdk 33+, поэтому строкой
    private const val NOTIF_PERM = "android.permission.POST_NOTIFICATIONS"

    fun hasFiles(a: Activity): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(a, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun requestFiles(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}"))
                )
            } catch (e: Exception) {
                a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(
                a, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 10
            )
        }
    }

    fun requestNotifications(a: Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(a, NOTIF_PERM) != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(a, arrayOf(NOTIF_PERM), 11)
    }
}
