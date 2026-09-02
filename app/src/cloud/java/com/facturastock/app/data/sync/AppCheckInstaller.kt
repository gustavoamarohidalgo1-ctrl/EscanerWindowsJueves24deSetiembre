package com.facturastock.app.data.sync

import com.google.firebase.FirebaseApp

/** Instala el provider App Check que corresponde al build type, sin exponerlo al runtime común. */
fun interface AppCheckInstaller {
    fun install(firebaseApp: FirebaseApp)
}
