/*
 * Copyright (C) 2008 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2012, ParanoidAndroid Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.ColorUtils;
import android.util.ExtendedPropertiesUtils;
import android.util.Slog;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.math.BigInteger;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.recent.RecentsActivity.NavigationCallback;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.DelegateViewHelper;
import com.android.systemui.statusbar.policy.DeadZone;

public class NavigationBarView extends LinearLayout implements NavigationCallback {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED= true;

    final static boolean ANIMATE_HIDE_TRANSITION = false; // turned off because it introduces unsightly delay when videos goes to full screen
    final static String NAVBAR_EDIT = "android.intent.action.NAVBAR_EDIT";

    private static boolean EDIT_MODE;
    private NavbarEditor mEditBar;
    private NavBarReceiver mNavBarReceiver;
    private OnClickListener mRecentsClickListener;
    private OnTouchListener mRecentsPreloadListener;
    private OnTouchListener mHomeSearchActionListener;

    protected IStatusBarService mBarService;
    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    int mBarSize;
    boolean mVertical;
    boolean mScreenOn;

    boolean mHidden, mLowProfile, mShowMenu;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;

    private Drawable mBackIcon, mBackLandIcon, mBackAltIcon, mBackAltLandIcon,
            mRecentsIcon, mRecentsLandIcon, mRecentsAltIcon, mRecentsAltLandIcon;
    
    private DelegateViewHelper mDelegateHelper;
    private DeadZone mDeadZone;

    private Canvas mCurrentCanvas;
    private Canvas mNewCanvas;
    private TransitionDrawable mTransition;
    private ColorUtils.ColorSettingInfo mLastBackgroundColor;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Slog.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    public void setDelegateView(View view) {
        mDelegateHelper.setDelegateView(view);
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mDelegateHelper.setBar(phoneStatusBar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        if (mDelegateHelper != null) {
            boolean ret = mDelegateHelper.onInterceptTouchEvent(event);
            if (ret) return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mDelegateHelper.onInterceptTouchEvent(event);
    }

    private H mHandler = new H();

    private View getHomeButton() {
        return mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_HOME);
    }

    private View getRecentsButton() {
        return mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_RECENT);
    }

    private View getBackButton() {
        return mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_BACK);
    }

    public static boolean getEditMode() {
        return EDIT_MODE;
    }
    
    protected void setListener(OnClickListener RecentsClickListener, OnTouchListener RecentsPreloadListener, OnTouchListener HomeSearchActionListener) {
        mRecentsClickListener = RecentsClickListener;
        mRecentsPreloadListener = RecentsPreloadListener;
        mHomeSearchActionListener = HomeSearchActionListener;
    }

    protected void toggleButtonListener(boolean enable) {
        View recentView = getRecentsButton();
        if (recentView != null) {
            recentView.setOnClickListener(enable ? mRecentsClickListener : null);
            recentView.setOnTouchListener(enable ? mRecentsPreloadListener : null);
        }
        View homeView = getHomeButton();
        // We cannot remove home button, so no need to null-check
        homeView.setOnTouchListener(enable ? mHomeSearchActionListener : null);
    }

    private void setButtonWithTagVisibility(String string, int visibility) {
        View findView = mCurrentView.findViewWithTag(string);
        if (findView != null) {
            findView.setVisibility(visibility);
        }
    }

    // for when home is disabled, but search isn't
    public View getSearchLight() {
        return mCurrentView.findViewById(R.id.search_light);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mHidden = false;

        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
        mShowMenu = false;
        mDelegateHelper = new DelegateViewHelper(this);
        updateResources();

        mNavBarReceiver = new NavBarReceiver();
        mContext.registerReceiver(mNavBarReceiver, new IntentFilter(NAVBAR_EDIT));
    }


    protected void updateResources() {
        final Resources res = mContext.getResources();
        getIcons(res);
    }

    private void getIcons(Resources res) {
        mBackIcon = res.getDrawable(R.drawable.ic_sysbar_back);
        mBackLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_land);
        mBackAltIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
        mBackAltLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
        mRecentsIcon = res.getDrawable(R.drawable.ic_sysbar_recent);
        mRecentsLandIcon = res.getDrawable(R.drawable.ic_sysbar_recent_land);
        mRecentsAltIcon = res.getDrawable(R.drawable.ic_sysbar_recent_clear);
        mRecentsAltLandIcon = res.getDrawable(R.drawable.ic_sysbar_recent_clear_land);

        // Only watch for per app color changes when the setting is in check
        if (ColorUtils.getPerAppColorState(mContext)) {

            // Reset all colors
            Bitmap currentBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            mCurrentCanvas = new Canvas(currentBitmap);
            mCurrentCanvas.drawColor(0xFF000000);
            BitmapDrawable currentBitmapDrawable = new BitmapDrawable(currentBitmap);

            Bitmap newBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            mNewCanvas = new Canvas(newBitmap);
            mNewCanvas.drawColor(0xFF000000);
            BitmapDrawable newBitmapDrawable = new BitmapDrawable(newBitmap);

            mTransition = new TransitionDrawable(new Drawable[]{currentBitmapDrawable, newBitmapDrawable});        
            setBackground(mTransition);

            mLastBackgroundColor = ColorUtils.getColorSettingInfo(mContext, Settings.System.NAV_BAR_COLOR);
            updateColor();

            mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.NAV_BAR_COLOR), false, new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateColor();
                    }});
        }
    }

    private void updateColor() {
        ColorUtils.ColorSettingInfo colorInfo = ColorUtils.getColorSettingInfo(mContext,
                Settings.System.NAV_BAR_COLOR);

        if (!colorInfo.lastColorString.equals(mLastBackgroundColor.lastColorString)) {
            // Only enable crossfade for transparent backdrops
            mTransition.setCrossFadeEnabled(!colorInfo.isLastColorOpaque);

            // Clear first layer, paint current color, reset mTransition to first layer
            mCurrentCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            mCurrentCanvas.drawColor(mLastBackgroundColor.lastColor);
            mTransition.resetTransition();

            // Clear second layer, paint new color, start mTransition
            mNewCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            mNewCanvas.drawColor(colorInfo.lastColor);
            mTransition.startTransition(colorInfo.speed);

            // Remember color for later
            mLastBackgroundColor = colorInfo;
        }
    }

    public class NavBarReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean edit = intent.getBooleanExtra("edit", false);
            boolean save = intent.getBooleanExtra("save", false);
            if (edit != EDIT_MODE) {
                EDIT_MODE = edit;
                if (EDIT_MODE) {
                    toggleButtonListener(false);
                    mEditBar.setupListeners();
                    mEditBar.updateKeys();
                } else {
                    mEditBar.dismissDialog();
                    if (save) {
                        mEditBar.saveKeys();
                    }
                    mEditBar.reInflate();
                    mEditBar = new NavbarEditor((ViewGroup) mCurrentView.findViewById(R.id.container), mVertical);
                    mEditBar.updateKeys();
                    toggleButtonListener(true);
                    if (save) {
                        mEditBar.updateLowLights(mCurrentView);
                    }
                    ((ViewGroup) mCurrentView.findViewById(R.id.mid_nav_buttons)).setLayoutTransition(
                            new LayoutTransition());
                }
            }
        }
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        getIcons(mContext.getResources());

        super.setLayoutDirection(layoutDirection);
    }

    public void notifyScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        setDisabledFlags(mDisabledFlags, true);
    }

    View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                setLowProfile(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };

    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(NavigationCallback.NAVBAR_BACK_HINT, hints, false);
    }

    public void setNavigationIconHints(int hints, boolean force) {
        setNavigationIconHints(NavigationCallback.NAVBAR_BACK_HINT, hints, force);
    }

    @Override
    public void setNavigationIconHints(int button, int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;

        if (DEBUG) {
            android.widget.Toast.makeText(mContext,
                "Navigation icon hints = " + hints+" button = "+button,
                500).show();
        }

        mNavigationIconHints = hints;

        getHomeButton().setAlpha(
            (0 != (hints & StatusBarManager.NAVIGATION_HINT_HOME_NOP)) ? 0.5f : 1.0f);

        View back = getBackButton();
        if(back != null) {
            back.setAlpha(
                (0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_NOP)) ? 0.5f : 1.0f);
        }

        View recent = getRecentsButton();
        if (recent != null) {
            recent.setAlpha(
                (0 != (hints & StatusBarManager.NAVIGATION_HINT_RECENT_NOP)) ? 0.5f : 1.0f);
        }

        if (button == NavigationCallback.NAVBAR_BACK_HINT) {
            ((ImageView)getBackButton()).setImageDrawable(
                (0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT))
                    ? (mVertical ? mBackAltLandIcon : mBackAltIcon)
                    : (mVertical ? mBackLandIcon : mBackIcon));
        } else if (button == NavigationCallback.NAVBAR_RECENTS_HINT) {
            if (recent != null) {
                ((ImageView)recent).setImageDrawable(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_RECENT_ALT))
                        ? (mVertical ? mRecentsAltLandIcon : mRecentsAltIcon)
                        : (mVertical ? mRecentsLandIcon : mRecentsIcon));
            }
        }

       // ((ImageView)getRecentsButton()).setImageDrawable(mVertical ? mRecentLandIcon : mRecentIcon);

        setDisabledFlags(mDisabledFlags, true);
    }

    @Override
    public int getNavigationIconHints() {
        return mNavigationIconHints;
    }

    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean disableSearch = ((disabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);

        if (SLIPPERY_WHEN_DISABLED) {
            setSlippery(disableHome && disableRecent && disableBack && disableSearch);
        }

        if (!mScreenOn && mCurrentView != null) {
            ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
            LayoutTransition lt = navButtons == null ? null : navButtons.getLayoutTransition();
            if (lt != null) {
                lt.disableTransitionType(
                        LayoutTransition.CHANGE_APPEARING | LayoutTransition.CHANGE_DISAPPEARING |
                        LayoutTransition.APPEARING | LayoutTransition.DISAPPEARING);
            }
        }

        setButtonWithTagVisibility(NavbarEditor.NAVBAR_BACK, disableBack ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_HOME, disableHome ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_RECENT, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_RECENT, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_ALWAYS_MENU, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_MENU_BIG, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_SEARCH, disableRecent ? View.INVISIBLE : View.VISIBLE);
        getSearchLight().setVisibility((disableHome && !disableSearch) ? View.VISIBLE : View.GONE);
    }

    public void setSlippery(boolean newSlippery) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            boolean oldSlippery = (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0;
            if (!oldSlippery && newSlippery) {
                lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else if (oldSlippery && !newSlippery) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
        }
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show) return;

        mShowMenu = show;

        setButtonWithTagVisibility(NavbarEditor.NAVBAR_CONDITIONAL_MENU, mShowMenu ? View.VISIBLE : View.INVISIBLE);
    }

    public void setLowProfile(final boolean lightsOut) {
        setLowProfile(lightsOut, true, false);
    }

    public void setLowProfile(final boolean lightsOut, final boolean animate, final boolean force) {
        if (!force && lightsOut == mLowProfile) return;

        mLowProfile = lightsOut;

        if (DEBUG) Slog.d(TAG, "setting lights " + (lightsOut?"out":"on"));

        final View navButtons = mCurrentView.findViewById(R.id.nav_buttons);
        final View lowLights = mCurrentView.findViewById(R.id.lights_out);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        if (!animate) {
            navButtons.setAlpha(lightsOut ? 0f : 1f);

            lowLights.setAlpha(lightsOut ? 1f : 0f);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            navButtons.animate()
                .alpha(lightsOut ? 0f : 1f)
                .setDuration(lightsOut ? 750 : 250)
                .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                .alpha(lightsOut ? 1f : 0f)
                .setDuration(lightsOut ? 750 : 250)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        lowLights.setVisibility(View.GONE);
                    }
                })
                .start();
        }
    }

    public void setHidden(final boolean hide) {
        if (hide == mHidden) return;

        mHidden = hide;
        Slog.d(TAG,
            (hide ? "HIDING" : "SHOWING") + " navigation bar");

        // bring up the lights no matter what
        setLowProfile(false);
    }

    @Override
    public void onFinishInflate() {
        mRotatedViews[Configuration.ORIENTATION_PORTRAIT] = findViewById(R.id.rot0);
        mRotatedViews[Configuration.ORIENTATION_LANDSCAPE] = findViewById(R.id.rot90);
        mCurrentView = mRotatedViews[mContext.getResources().getConfiguration().orientation];
        if (ColorUtils.getPerAppColorState(mContext)) {
            updateColor();
        }
    }

    public void reorient() {
        int rot = mContext.getResources().getConfiguration().orientation;
        for (int i=1; i<3; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);
        if (NavbarEditor.isDevicePhone()) {
            rot = mDisplay.getRotation();
            mVertical = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270);
        } else {
            mVertical = getWidth() > 0 && getHeight() > getWidth();
        }
        mEditBar = new NavbarEditor((ViewGroup) mCurrentView.findViewById(R.id.container), mVertical);
        mEditBar.updateKeys();
        mEditBar.updateLowLights(mCurrentView);
        toggleButtonListener(true);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);

        // force the low profile & disabled states into compliance
        setLowProfile(mLowProfile, false, true /* force */);
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Slog.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        setNavigationIconHints(mNavigationIconHints, true);
        // Reset recents hints after reorienting, if recents icon is present
        View recent = getRecentsButton();
        if(recent != null) {
            ((ImageView)recent).setImageDrawable(mVertical
                    ? mRecentsLandIcon : mRecentsIcon);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        ViewGroup mid_nav = (ViewGroup) mCurrentView.findViewById(R.id.mid_nav_buttons);
        View vViews[] = new View[mid_nav.getChildCount()];
        for (int cc = 0;cc < mid_nav.getChildCount(); cc++) {
            vViews[cc] = mid_nav.getChildAt(cc);
        }
        mDelegateHelper.setInitialTouchRegion(vViews);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Slog.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        System.out.println(newVertical + " vs " + mVertical);
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Slog.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        mDisplay.getRealSize(size);

        pw.println(String.format("      this: " + PhoneStatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: " 
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(mCurrentView.getId()),
                        mCurrentView.getWidth(), mCurrentView.getHeight(),
                        visibilityToString(mCurrentView.getVisibility())));

        pw.println(String.format("      disabled=0x%08x vertical=%s hidden=%s low=%s menu=%s",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        mHidden ? "true" : "false",
                        mLowProfile ? "true" : "false",
                        mShowMenu ? "true" : "false"));

        final View back = getBackButton();
        final View home = getHomeButton();
        final View recent = getRecentsButton();

        pw.println("      home: "
                + PhoneStatusBar.viewInfo(home)
                + " " + visibilityToString(home.getVisibility())
                );
        if(back != null) {
            pw.println("      back: "
                    + PhoneStatusBar.viewInfo(back)
                    + " " + visibilityToString(back.getVisibility())
                    );
        }
        if(recent != null) {
            pw.println("      recent: "
                    + PhoneStatusBar.viewInfo(recent)
                    + " " + visibilityToString(recent.getVisibility())
                    );
        }
        pw.println("    }");
    }

}
