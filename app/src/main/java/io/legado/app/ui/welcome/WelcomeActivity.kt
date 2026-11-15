package io.legado.app.ui.welcome

import android.content.Intent
import android.os.Bundle
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.postDelayed
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.windowSize
import splitties.init.appCtx

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            // 避免从桌面启动程序后，会重新实例化入口类的activity
            finish()
        } else {
            val welcomeShowTime = getPrefInt(PreferKey.welcomeShowTime, 500)
            if (welcomeShowTime == 0) {
                startMainActivity()
            } else {
                binding.root.postDelayed(welcomeShowTime.toLong()) { startMainActivity() }
            }
        }
        binding.ivBook.setColorFilter(accentColor)
        binding.vwTitleLine.setBackgroundColor(accentColor)
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        if (getPrefBoolean(PreferKey.customWelcome)) {
            kotlin.runCatching {
                when (ThemeConfig.getTheme()) {
                    Theme.Dark -> {
                        getPrefString(PreferKey.welcomeImageDark)?.let { path ->
                            if (path.endsWith(".9.png")) {
                                BitmapUtils.decodeNinePatchDrawable(path)?.let {
                                    window.decorView.background = it
                                }
                            } else {
                                val size = windowManager.windowSize
                                BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels)?.let {
                                    window.decorView.background = it.toDrawable(resources)
                                }
                            }
                        }
                        binding.tvLegado.visible(AppConfig.welcomeShowTextDark)
                        binding.ivBook.visible(AppConfig.welcomeShowIconDark)
                        binding.tvGzh.visible(AppConfig.welcomeShowTextDark)
                        return
                    }
                    else -> {
                        getPrefString(PreferKey.welcomeImage)?.let { path ->
                            if (path.endsWith(".9.png")) {
                                BitmapUtils.decodeNinePatchDrawable(path)?.let {
                                    window.decorView.background = it
                                }
                            } else {
                                val size = windowManager.windowSize
                                BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels)?.let {
                                    window.decorView.background = it.toDrawable(resources)
                                }
                            }
                        }
                        binding.tvLegado.visible(AppConfig.welcomeShowText)
                        binding.ivBook.visible(AppConfig.welcomeShowIcon)
                        binding.tvGzh.visible(AppConfig.welcomeShowText)
                        return
                    }
                }
            }
        }
        super.upBackgroundImage()
    }

    /**
     * 启动主界面，并按设置决定是否直接恢复上次阅读。
     *
     * 这里刻意**不在主线程查数据库**：冷启动第一次访问 Room 要打开（必要时迁移）数据库，
     * 库越大耗时越长；书架/书源条目多、距上次使用越久，这一步越慢。原来在主线程查
     * `lastReadBook` 会把首帧连同返回键一起卡住，表现就是"点开图标后整屏卡死"。
     * 现在先把主界面拉起来（它同时是阅读页的返回栈底），再用后台协程查库、按书的实际类型分流。
     *
     * 分流也不能再用 `lastReadBook`：那条查询只认 text 类型，漫画书（type=image）会被过滤掉，
     * 漫画用户重开 App 就会被丢进上一次看过的小说里。
     */
    private fun startMainActivity() {
        // 主界面先落地：任何后续步骤失败，用户至少有一个可交互的界面可以返回
        appCtx.startActivity<MainActivity>()
        finish()
        if (!getPrefBoolean(PreferKey.defaultToRead)) return
        Coroutine.async {
            val startAt = System.currentTimeMillis()
            val book = runCatching { appDb.bookDao.lastReadBookAny }.getOrNull()
            val cost = System.currentTimeMillis() - startAt
            // 只在明显变慢时记一条：这是"冷启动卡住"最直接的量化证据，
            // 正常 <100ms 不写，避免污染日志面板
            if (cost > SLOW_RESTORE_MS) {
                AppLog.put("冷启动恢复阅读：查询上次阅读耗时 ${cost}ms，book=${book?.name}")
            }
            book
        }.onSuccess { book ->
            // 显式带 bookUrl 进入，避免阅读器再用 lastReadBook 猜书、加载错章节
            if (book != null) appCtx.startActivityForBook(book)
        }
    }

    companion object {
        /** 「恢复上次阅读」查库耗时超过该值就记一条日志，见 [startMainActivity] */
        private const val SLOW_RESTORE_MS = 500L
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()
class Launcher7 : WelcomeActivity()
class Launcher8 : WelcomeActivity()