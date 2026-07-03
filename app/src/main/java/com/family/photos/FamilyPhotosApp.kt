package com.family.photos

import android.app.Application
import com.family.photos.util.SupabaseUtil

class FamilyPhotosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SupabaseUtil.init(this)
    }
}
