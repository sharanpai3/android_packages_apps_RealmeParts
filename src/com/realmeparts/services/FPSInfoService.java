/*
 * Copyright (C) 2019 The OmniROM Project
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

package com.realmeparts;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.dreams.IDreamManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.FileReader;

public class FPSInfoService extends Service {
    private static final String MEASURED_FPS = "/sys/module/primary_display/parameters/display_framerate_main";
    private final String TAG = "FPSInfoService";
    private View mView;
    private Thread mCurFPSThread;
    private String mFps = null;
    private int mPaddingLeft;
    private int mPaddingTop;
    private int mPaddingRight;
    private int mPaddingBottom;
    private IDreamManager mDreamManager;
    private final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                Log.d(TAG, "ACTION_SCREEN_ON " + isDozeMode());
                if (!isDozeMode()) {
                    startThread();
                    mView.setVisibility(View.VISIBLE);
                }
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                Log.d(TAG, "ACTION_SCREEN_OFF");
                mView.setVisibility(View.GONE);
                stopThread();
            }
        }
    };

    private static String readOneLine(String fname) {
        BufferedReader br;
        String line = null;
        try {
            br = new BufferedReader(new FileReader(fname), 512);
            try {
                line = br.readLine();
            } finally {
                br.close();
            }
        } catch (Exception e) {
            return null;
        }
        return line;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mView = new FPSView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.RIGHT | Gravity.TOP;
        params.setTitle("FPS Info");

        startThread();

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService("dreams"));
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateReceiver, screenStateFilter);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.addView(mView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopThread();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(mView);
        mView = null;
        unregisterReceiver(mScreenStateReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isDozeMode() {
        try {
            if (mDreamManager != null && mDreamManager.isDreaming()) {
                return true;
            }
        } catch (RemoteException e) {
            return false;
        }
        return false;
    }

    private void startThread() {
        Log.d(TAG, "started CurFPSThread");
        mCurFPSThread = new CurFPSThread(mView.getHandler());
        mCurFPSThread.start();
    }

    private void stopThread() {
        if (mCurFPSThread != null && mCurFPSThread.isAlive()) {
            Log.d(TAG, "stopping CurFPSThread");
            mCurFPSThread.interrupt();
            try {
                mCurFPSThread.join();
            } catch (InterruptedException e) {
            }
        }
        mCurFPSThread = null;
    }

    private class FPSView extends View {
        private final Paint mOnlinePaint;
        private final float mAscent;
        private final int mFH;
        private final int mMaxWidth;

        private int mNeededWidth;
        private int mNeededHeight;

        private boolean mDataAvail;

        private final Handler mCurFPSHandler = new Handler() {
            public void handleMessage(Message msg) {
                if (msg.obj == null) {
                    return;
                }
                if (msg.what == 1) {
                    String msgData = (String) msg.obj;
                    msgData = msgData.substring(0, Math.min(msgData.length(), 9));
                    mFps = msgData;
                    mDataAvail = true;
                    updateDisplay();
                }
            }
        };

        FPSView(Context c) {
            super(c);
            float density = c.getResources().getDisplayMetrics().density;
            int paddingPx = Math.round(9 * density);
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
            setBackgroundColor(Color.argb(0x0, 0, 0, 0));

            final int textSize = Math.round(15 * density);

            Typeface typeface = Typeface.create("googlesans", Typeface.BOLD);

            mOnlinePaint = new Paint();
            mOnlinePaint.setTypeface(typeface);
            mOnlinePaint.setAntiAlias(true);
            mOnlinePaint.setTextSize(textSize);
            mOnlinePaint.setColor(Color.WHITE);
            mOnlinePaint.setShadowLayer(5.0f, 0.0f, 0.0f, Color.BLACK);

            mAscent = mOnlinePaint.ascent();
            float descent = mOnlinePaint.descent();
            mFH = (int) (descent - mAscent + .5f);

            final String maxWidthStr = "fps: 60.1";
            mMaxWidth = (int) mOnlinePaint.measureText(maxWidthStr);

            updateDisplay();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mCurFPSHandler.removeMessages(1);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(resolveSize(mNeededWidth, widthMeasureSpec),
                    resolveSize(mNeededHeight, heightMeasureSpec));
        }

        private String getFPSInfoString() {
            return "fps: "+mFps;
        }

        @Override
        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!mDataAvail) {
                return;
            }

            final int W = mNeededWidth;
            final int RIGHT = getWidth() - 1;

            int x = RIGHT - mPaddingLeft;
            int top = mPaddingTop + 2;
            int bottom = mPaddingTop + mFH - 2;

            int y = mPaddingTop - (int) mAscent;

            String s = getFPSInfoString();
            canvas.drawText(s, RIGHT - mPaddingLeft - mMaxWidth,
                    y - 1, mOnlinePaint);
            y += mFH;
        }

        void updateDisplay() {
            if (!mDataAvail) {
                return;
            }

            int neededWidth = mPaddingLeft + mPaddingRight + mMaxWidth;
            int neededHeight = mPaddingTop + mPaddingBottom + 40;
            if (neededWidth != mNeededWidth || neededHeight != mNeededHeight) {
                mNeededWidth = neededWidth;
                mNeededHeight = neededHeight;
                requestLayout();
            } else {
                invalidate();
            }
        }

        public Handler getHandler() {
            return mCurFPSHandler;
        }
    }

    protected class CurFPSThread extends Thread {
        private final Handler mHandler;
        private boolean mInterrupt = false;

        public CurFPSThread(Handler handler) {
            mHandler = handler;
        }

        public void interrupt() {
            mInterrupt = true;
        }

        @Override
        public void run() {
            try {
                while (!mInterrupt) {
                    sleep(1000);
                    StringBuffer sb = new StringBuffer();
                    String fpsVal = FPSInfoService.readOneLine(MEASURED_FPS);
                    mHandler.sendMessage(mHandler.obtainMessage(1, fpsVal));
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
