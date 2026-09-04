package com.github.tvbox.osc.ui.activity

import android.os.Process
import android.view.KeyEvent
import android.view.MenuItem
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.LiveFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>() {

    var fragments = listOf<Fragment>(HomeFragment(), LiveFragment(), MyFragment())
    var useCacheConfig = false
    private var exitTime = 0L

    override fun init() {

        useCacheConfig = intent.extras?.getBoolean(IntentKey.CACHE_CONFIG_CHANGED, false)?:false

        // NavigationBarView(Material)会在 insets 分发时自动给自己加导航栏避让 padding,
        // 与 BaseActivity 统一的 content padding 叠成双重避让(三键导航下 tab 与导航栏间出现大段空白),
        // 替换为透传监听抵消掉,避让只由 content padding 承担
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.bottomNav) { _, insets -> insets }

        mBinding.vp.adapter = object : FragmentPagerAdapter(supportFragmentManager) {
            override fun getItem(position: Int): Fragment {
                return fragments[position]
            }

            override fun getCount(): Int {
                return fragments.size
            }
        }

        mBinding.bottomNav.setOnNavigationItemSelectedListener { menuItem: MenuItem ->
            mBinding.vp.setCurrentItem(menuItem.order, false)
            true
        }
        mBinding.vp.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                mBinding.bottomNav.menu.getItem(position).setChecked(true)
            }
        })
    }

    /** 切换底部 tab(直播标题栏返回/我的页入口/通知点击等统一入口) */
    fun goToTab(index: Int) {
        mBinding.vp.currentItem = index
    }

    override fun onBackPressed() {
        if (mBinding.vp.currentItem != 0) {
            // 直播 tab 内部有可回退层(侧边设置/全屏)时先消化,否则切回首页 tab
            val liveFragment = fragments[1] as LiveFragment
            if (liveFragment.isUiReady() && liveFragment.handleBack()) {
                return
            }
            mBinding.vp.currentItem = 0
            return
        }
        val homeFragment = fragments[0] as HomeFragment
        if (!homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[homeFragment.tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        // 直播 tab 内遥控器按键(换台/呼出频道列表)交给直播页处理
        if (mBinding.vp.currentItem == 1) {
            val liveFragment = fragments[1] as LiveFragment
            if (event != null && liveFragment.isUiReady()) {
                liveFragment.handleKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtils.showShort("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}
