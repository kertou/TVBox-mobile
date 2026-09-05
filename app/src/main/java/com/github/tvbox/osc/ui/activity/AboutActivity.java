package com.github.tvbox.osc.ui.activity;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.databinding.ActivityAboutBinding;

/**
 * 关于页(原底部弹窗 AboutDialog 改为独立页面)
 */
public class AboutActivity extends BaseVbActivity<ActivityAboutBinding> {

    @Override
    protected void init() {
        mBinding.titleBar.setNavigationOnClickListener(v -> finish());
        mBinding.tvVersion.setText("TVBox-Mobile v" + AppUtils.getAppVersionName());
    }
}
