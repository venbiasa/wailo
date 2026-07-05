package com.venbiasa.waylay.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// Exercises OkHttp calls with WaylayInterceptor installed.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Waylay Sample - M0 skeleton" })
    }
}
