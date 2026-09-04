package com.github.tvbox.osc.ui.fragment;

import com.blankj.utilcode.util.AppUtils;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.databinding.FragmentMyBinding;
import com.github.tvbox.osc.ui.activity.AboutActivity;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.MainActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.activity.SubscriptionActivity;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class MyFragment extends BaseVbFragment<FragmentMyBinding> {


    @Override
    protected void init() {
        mBinding.tvVersion.setText("v"+ AppUtils.getAppVersionName());

        mBinding.tvLive.setOnClickListener(v -> {
            if (mActivity instanceof MainActivity) ((MainActivity) mActivity).goToTab(1);
        });

        mBinding.tvSetting.setOnClickListener(v -> jumpActivity(SettingActivity.class));

        mBinding.tvHistory.setOnClickListener(v -> jumpActivity(HistoryActivity.class));

        mBinding.tvFavorite.setOnClickListener(v -> jumpActivity(CollectActivity.class));

        mBinding.tvCache.setOnClickListener(v -> {
            if (mActivity instanceof MainActivity) ((MainActivity) mActivity).goToTab(2);
        });

        mBinding.llSubscription.setOnClickListener(v -> jumpActivity(SubscriptionActivity.class));

        mBinding.llAbout.setOnClickListener(v -> jumpActivity(AboutActivity.class));
    }

}
