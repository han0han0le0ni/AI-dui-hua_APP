package com.personal.roleplay

import android.app.Application
import com.personal.roleplay.di.AppContainer

class RoleplayApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 触发一次构造，内置角色种子数据与数据库初始化随之进行
        container
    }
}
