package dev.isatlas

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Give the temporary instrumentation package an identifiable launcher. */
class VerificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent().setClassName("dev.isatlas", "dev.isatlas.MainActivity"))
        finish()
    }
}
