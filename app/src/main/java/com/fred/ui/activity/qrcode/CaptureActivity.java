/*
 * Copyright (c) 2015 [1076559197@qq.com | tchen0707@gmail.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fred.ui.activity.qrcode;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.fred.R;
import com.fred.ui.activity.qrcode.camera.CameraManager;
import com.fred.ui.activity.qrcode.decode.DecodeUtils;
import com.fred.ui.activity.qrcode.utils.BeepManager;
import com.fred.ui.activity.qrcode.utils.InactivityTimer;
import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.ObjectAnimator;
import com.nineoldandroids.animation.PropertyValuesHolder;
import com.nineoldandroids.animation.ValueAnimator;
import com.nineoldandroids.view.ViewHelper;

import java.io.IOException;
import java.util.regex.Pattern;


/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public class CaptureActivity extends Activity implements SurfaceHolder.Callback {

    public String TAG_LOG = this.getClass().getSimpleName();
    public static final int IMAGE_PICKER_REQUEST_CODE = 100;

    private SurfaceView capturePreview;
    private ImageView captureErrorMask;
    private ImageView captureScanMask;
    private FrameLayout captureCropView;
    private Button capturePictureBtn;
    private Button captureLightBtn;
    private RadioGroup captureModeGroup;
    private RelativeLayout captureContainer;

    private CameraManager cameraManager;
    private CaptureActivityHandler handler;

    private boolean hasSurface;
    private boolean isLightOn;

    private InactivityTimer mInactivityTimer;
    private BeepManager mBeepManager;

    private int mQrcodeCropWidth = 0;
    private int mQrcodeCropHeight = 0;
    private int mBarcodeCropWidth = 0;
    private int mBarcodeCropHeight = 0;

    private ObjectAnimator mScanMaskObjectAnimator = null;

    private Rect cropRect;
    private int dataMode = DecodeUtils.DECODE_DATA_MODE_QRCODE;

    View dialogView;
    Dialog dialog;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(R.anim.right_in,0);
        setContentView(R.layout.activity_capture);
        setTranslucentStatus(true);
        capturePreview = (SurfaceView) findViewById(R.id.capture_preview);
        captureErrorMask = (ImageView) findViewById(R.id.capture_error_mask);
        captureScanMask = (ImageView) findViewById(R.id.capture_scan_mask);
        captureCropView = (FrameLayout) findViewById(R.id.capture_crop_view);
        capturePictureBtn = (Button) findViewById(R.id.capture_picture_btn);
        captureLightBtn = (Button) findViewById(R.id.capture_light_btn);
        captureModeGroup = (RadioGroup) findViewById(R.id.capture_mode_group);
        captureContainer = (RelativeLayout) findViewById(R.id.capture_container);
        start();
    }

    private void start() {

        hasSurface = false;
        mInactivityTimer = new InactivityTimer(this);
        mBeepManager = new BeepManager(this);

        initCropViewAnimator();

        capturePictureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //TODO 选择图片识别
//                Intent intent = new ntent(CaptureActivity.this, CommonImagePickerListActivity.class);
//                startActivityForResult(intent, IMAGE_PICKER_REQUEST_CODE);
            }
        });

        captureLightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isLightOn) {
                    cameraManager.setTorch(false);
                    captureLightBtn.setSelected(false);
                } else {
                    cameraManager.setTorch(true);
                    captureLightBtn.setSelected(true);
                }
                isLightOn = !isLightOn;
            }
        });

        captureModeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.capture_mode_barcode) {
                    PropertyValuesHolder qr2barWidthVH = PropertyValuesHolder.ofFloat("width",
                            1.0f, (float) mBarcodeCropWidth / mQrcodeCropWidth);
                    PropertyValuesHolder qr2barHeightVH = PropertyValuesHolder.ofFloat("height",
                            1.0f, (float) mBarcodeCropHeight / mQrcodeCropHeight);
                    ValueAnimator valueAnimator = ValueAnimator.ofPropertyValuesHolder(qr2barWidthVH, qr2barHeightVH);
                    valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            Float fractionW = (Float) animation.getAnimatedValue("width");
                            Float fractionH = (Float) animation.getAnimatedValue("height");

                            RelativeLayout.LayoutParams parentLayoutParams = (RelativeLayout.LayoutParams) captureCropView.getLayoutParams();
                            parentLayoutParams.width = (int) (mQrcodeCropWidth * fractionW);
                            parentLayoutParams.height = (int) (mQrcodeCropHeight * fractionH);
                            captureCropView.setLayoutParams(parentLayoutParams);
                        }
                    });
                    valueAnimator.addListener(new Animator.AnimatorListener() {
                        @Override
                        public void onAnimationStart(Animator animation) {

                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            initCrop();
                            setDataMode(DecodeUtils.DECODE_DATA_MODE_BARCODE);
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {

                        }

                        @Override
                        public void onAnimationRepeat(Animator animation) {

                        }
                    });
                    valueAnimator.start();


                } else if (checkedId == R.id.capture_mode_qrcode) {
                    PropertyValuesHolder bar2qrWidthVH = PropertyValuesHolder.ofFloat("width",
                            1.0f, (float) mQrcodeCropWidth / mBarcodeCropWidth);
                    PropertyValuesHolder bar2qrHeightVH = PropertyValuesHolder.ofFloat("height",
                            1.0f, (float) mQrcodeCropHeight / mBarcodeCropHeight);
                    ValueAnimator valueAnimator = ValueAnimator.ofPropertyValuesHolder(bar2qrWidthVH, bar2qrHeightVH);
                    valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            Float fractionW = (Float) animation.getAnimatedValue("width");
                            Float fractionH = (Float) animation.getAnimatedValue("height");

                            RelativeLayout.LayoutParams parentLayoutParams = (RelativeLayout.LayoutParams) captureCropView.getLayoutParams();
                            parentLayoutParams.width = (int) (mBarcodeCropWidth * fractionW);
                            parentLayoutParams.height = (int) (mBarcodeCropHeight * fractionH);
                            captureCropView.setLayoutParams(parentLayoutParams);
                        }
                    });
                    valueAnimator.addListener(new Animator.AnimatorListener() {
                        @Override
                        public void onAnimationStart(Animator animation) {

                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            initCrop();
                            setDataMode(DecodeUtils.DECODE_DATA_MODE_QRCODE);
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {

                        }

                        @Override
                        public void onAnimationRepeat(Animator animation) {

                        }
                    });
                    valueAnimator.start();
                }
            }
        });
    }

    private void initCropViewAnimator() {
        mQrcodeCropWidth = getResources().getDimensionPixelSize(R.dimen.qrcode_crop_width);
        mQrcodeCropHeight = getResources().getDimensionPixelSize(R.dimen.qrcode_crop_height);

        mBarcodeCropWidth = getResources().getDimensionPixelSize(R.dimen.barcode_crop_width);
        mBarcodeCropHeight = getResources().getDimensionPixelSize(R.dimen.barcode_crop_height);
    }

    public Handler getHandler() {
        return handler;
    }

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    public void initCrop() {
        int cameraWidth = cameraManager.getCameraResolution().y;
        int cameraHeight = cameraManager.getCameraResolution().x;

        int[] location = new int[2];
        captureCropView.getLocationInWindow(location);

        int cropLeft = location[0];
        int cropTop = location[1];

        int cropWidth = captureCropView.getWidth();
        int cropHeight = captureCropView.getHeight();

        int containerWidth = captureContainer.getWidth();
        int containerHeight = captureContainer.getHeight();

        int x = cropLeft * cameraWidth / containerWidth;
        int y = cropTop * cameraHeight / containerHeight;

        int width = cropWidth * cameraWidth / containerWidth;
        int height = cropHeight * cameraHeight / containerHeight;

        setCropRect(new Rect(x, y, width + x, height + y));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
        // want to open the camera driver and measure the screen size if we're going to show the help on
        // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
        // off screen.
        cameraManager = new CameraManager(getApplication());

        handler = null;

        if (hasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(capturePreview.getHolder());
        } else {
            // Install the callback and wait for surfaceCreated() to init the camera.
            capturePreview.getHolder().addCallback(this);
        }

        mInactivityTimer.onResume();
    }

    @Override
    protected void onPause() {
        if (handler != null) {
            handler.quitSynchronously();
            handler = null;
        }

        mBeepManager.close();
        mInactivityTimer.onPause();
        cameraManager.closeDriver();

        if (!hasSurface) {
            capturePreview.getHolder().removeCallback(this);
        }

        if (null != mScanMaskObjectAnimator && mScanMaskObjectAnimator.isStarted()) {
            mScanMaskObjectAnimator.cancel();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mInactivityTimer.shutdown();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG_LOG, "*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!hasSurface) {
            hasSurface = true;
//            initCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        initCamera(holder);
    }

    /**
     * A valid barcode has been found, so give an indication of success and show the results.
     */
    public void handleDecode(String result, Bundle bundle) {
        mInactivityTimer.onActivity();
        mBeepManager.playBeepSoundAndVibrate();

        if (!TextUtils.isEmpty(result)
                && isUrl(result)) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(result));
            startActivity(intent);
        } else {
            bundle.putString(ResultActivity.BUNDLE_KEY_SCAN_RESULT, result);
            Intent intent = new Intent(this, ResultActivity.class);
            if (null != bundle) {
                intent.putExtras(bundle);
            }
            startActivity(intent);
        }
    }

    private void onCameraPreviewSuccess() {
        initCrop();
        captureErrorMask.setVisibility(View.GONE);

        ViewHelper.setPivotX(captureScanMask, 0.0f);
        ViewHelper.setPivotY(captureScanMask, 0.0f);

        mScanMaskObjectAnimator = ObjectAnimator.ofFloat(captureScanMask, "scaleY", 0.0f, 1.0f);
        mScanMaskObjectAnimator.setDuration(2000);
        mScanMaskObjectAnimator.setInterpolator(new DecelerateInterpolator());
        mScanMaskObjectAnimator.setRepeatCount(-1);
        mScanMaskObjectAnimator.setRepeatMode(ObjectAnimator.RESTART);
        mScanMaskObjectAnimator.start();
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (cameraManager.isOpen()) {
            Log.w(TAG_LOG, "initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        try {
            cameraManager.openDriver(surfaceHolder);
            // Creating the handler starts the preview, which can also throw a RuntimeException.
            if (handler == null) {
                handler = new CaptureActivityHandler(this, cameraManager);
            }

            onCameraPreviewSuccess();
        } catch (IOException ioe) {
            Log.w(TAG_LOG, ioe);
            showPermissionError();
        } catch (RuntimeException e) {
            // Barcode Scanner has seen crashes in the wild of this variety:
            // java.?lang.?RuntimeException: Fail to connect to camera service
            Log.w(TAG_LOG, "Unexpected error initializing camera", e);
            showPermissionError();
        }

    }

    public void showPermissionError(){
            dialogView = LayoutInflater.from(this).inflate(
                    R.layout.qr_dialog, null);
            dialog = new AlertDialog.Builder(this).create();
            dialog.show();
            dialog.getWindow().setContentView(dialogView);
            dialog.setCanceledOnTouchOutside(true);
            int width = this.getWindowManager().getDefaultDisplay().getWidth();// 得到当前显示设备的宽度，单位是像素
            android.view.WindowManager.LayoutParams params = dialog.getWindow()
                    .getAttributes();// 得到这个dialog界面的参数对象
            params.width = width - (width / 6);// 设置dialog的界面宽度
            params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;// 设置dialog高度为包裹内容
            params.gravity = Gravity.CENTER;// 设置dialog的重心
            dialog.getWindow().setAttributes(params);
            TextView tv_confirm = (TextView) dialog.findViewById(R.id.tv_confirm);
            TextView tv_dialog_hint = (TextView) dialog.findViewById(R.id.tv_dialog_hint);
            tv_dialog_hint.setText("请给予打开照相机权限");
            tv_confirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View arg0) {
                    dialog.dismiss();
                    finish();
                }
            });
            dialog.setCancelable(false);// 屏蔽取消（back）键
            dialog.setCanceledOnTouchOutside(false);// 屏蔽非dialog区域点击dialog关闭
    }


    public Rect getCropRect() {
        return cropRect;
    }

    public void setCropRect(Rect cropRect) {
        this.cropRect = cropRect;
    }

    public int getDataMode() {
        return dataMode;
    }

    public void setDataMode(int dataMode) {
        this.dataMode = dataMode;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }

        //TODO 选择图片回调
//        if (requestCode == IMAGE_PICKER_REQUEST_CODE) {
//            String imagePath = data.getStringExtra(CommonImagePickerDetailActivity
//                    .KEY_BUNDLE_RESULT_IMAGE_PATH);
//
//            if (!CommonUtils.isEmpty(imagePath)) {
//                ImageLoader.getInstance().loadImage("file://" + imagePath, new ImageLoadingListener() {
//                    @Override
//                    public void onLoadingStarted(String imageUri, View view) {
//                    }
//
//                    @Override
//                    public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
//
//                    }
//
//                    @Override
//                    public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
//                        String resultZxing = new DecodeUtils(DecodeUtils.DECODE_DATA_MODE_ALL)
//                                .decodeWithZxing(loadedImage);
//                        String resultZbar = new DecodeUtils(DecodeUtils.DECODE_DATA_MODE_ALL)
//                                .decodeWithZbar(loadedImage);
//
//                        if (!CommonUtils.isEmpty(resultZbar)) {
//                            Bundle extras = new Bundle();
//                            extras.putInt(DecodeThread.DECODE_MODE, DecodeUtils.DECODE_MODE_ZBAR);
//
//                            handleDecode(resultZbar, extras);
//                        } else if (!CommonUtils.isEmpty(resultZxing)) {
//                            Bundle extras = new Bundle();
//                            extras.putInt(DecodeThread.DECODE_MODE, DecodeUtils.DECODE_MODE_ZXING);
//
//                            handleDecode(resultZxing, extras);
//                        } else {
//                            showToast("No data resolved");
//                        }
//                    }
//
//                    @Override
//                    public void onLoadingCancelled(String imageUri, View view) {
//
//                    }
//                });
//            }
//        }
    }
    //====base里的功能====
    /**
     * set status bar translucency
     *
     * @param on
     */
    protected void setTranslucentStatus(boolean on) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Window win = getWindow();
            WindowManager.LayoutParams winParams = win.getAttributes();
            final int bits = WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
            if (on) {
                winParams.flags |= bits;
            } else {
                winParams.flags &= ~bits;
            }
            win.setAttributes(winParams);
        }
    }

    public boolean isUrl(String url) {
        Pattern pattern = Pattern.compile("^([hH][tT]{2}[pP]://|[hH][tT]{2}[pP][sS]://)(([A-Za-z0-9-~]+).)+([A-Za-z0-9-~\\/])+$");
        return pattern.matcher(url).matches();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0,R.anim.right_out);
    }
}
