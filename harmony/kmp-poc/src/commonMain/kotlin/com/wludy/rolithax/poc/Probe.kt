package com.wludy.rolithax.poc

import com.wludy.rolithax.launcher.data.resources.ResourceFilter

fun sharedProbe(): String = "Rolithax KMP"

fun resourceFilterKey(type: String, query: String): String = ResourceFilter(type, query).key
