package com.github.tvbox.osc.ui.dialog;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ColorUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.databinding.DialogAllChannelBinding;
import com.github.tvbox.osc.ui.fragment.LiveFragment;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupNewAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemNewAdapter;
import com.github.tvbox.osc.ui.widget.GridSpacingItemDecoration;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.core.DrawerPopupView;
import com.lxj.xpopup.enums.PopupPosition;
import com.lxj.xpopup.interfaces.OnSelectListener;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class AllChannelsRightDialog extends DrawerPopupView {

    private final LiveFragment mActivity;
    private com.github.tvbox.osc.databinding.DialogAllChannelBinding mBinding;

    public AllChannelsRightDialog(@NonNull @NotNull LiveFragment fragment) {
        super(fragment.requireActivity());
        mActivity = fragment;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_all_channel;
    }

    /**
     * decorFitsSystemWindows(false) 后 XPopup 的 getDecorViewInvisibleHeight 恒非 0,
     * 默认 back 走"隐藏软键盘"分支被吞不关弹窗,这里显式 dismiss
     */
    @Override
    protected boolean onBackPressed() {
        dismiss();
        return true;
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        mBinding = DialogAllChannelBinding.bind(getPopupImplView());
        initChannelGroupView();
        initLiveChannelView();
    }

    private void initChannelGroupView() {
        mBinding.mGroupGridView.setHasFixedSize(true);
        mBinding.mGroupGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));

        if (mActivity.liveChannelGroupAdapter!=null){
            mBinding.mGroupGridView.setAdapter(mActivity.liveChannelGroupAdapter);
        }

    }
    private void initLiveChannelView() {
        mBinding.mChannelGridView.setHasFixedSize(true);
        mBinding.mChannelGridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 1, false));

        if (mActivity.liveChannelItemAdapter!=null){
            mBinding.mChannelGridView.setAdapter(mActivity.liveChannelItemAdapter);
        }
    }

}