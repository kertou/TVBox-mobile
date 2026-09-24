package com.github.tvbox.osc.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冷启动 ULP: baselineprofile 插件跑本规则收集启动路径类/方法, 生成 baseline profile
 * 嵌入 release APK(运行时经 ProfileInstaller 预编译), 缩短点击图标→首帧的时长。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = "com.kertou.tvplayer",
        includeInStartupProfile = true,
        maxIterations = 5,
        stableIterations = 2
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle(2000)
        // 停留覆盖首帧后的内容加载路径(订阅/首页渲染)
        Thread.sleep(1500)
    }
}
