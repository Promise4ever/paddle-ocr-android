package com.example.paddleocr

import android.app.Activity
import android.content.Intent

/**
 * 底部导航切换：三个一级页面互不堆叠，
 * 切到其他页时结束当前页，返回键从任一页直接退出 App。
 */
object BottomNav {
    fun onTabSelected(activity: Activity, itemId: Int) {
        val target: Class<*>? = when (itemId) {
            R.id.nav_ocr -> if (activity !is MainActivity) MainActivity::class.java else null
            R.id.nav_history -> if (activity !is HistoryActivity) HistoryActivity::class.java else null
            R.id.nav_settings -> if (activity !is SettingsActivity) SettingsActivity::class.java else null
            else -> null
        }
        val cls = target ?: return
        activity.startActivity(
            Intent(activity, cls).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        // 点按切换，不使用滑动动画
        activity.overridePendingTransition(0, 0)
        activity.finish()
        activity.overridePendingTransition(0, 0)
    }
}
