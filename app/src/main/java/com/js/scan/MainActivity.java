package com.js.scan;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.js.scan.databinding.ActivityMainBinding;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final int REQUEST_CODE_PICK_IMAGE = 11;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private ActivityMainBinding binding;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private boolean isScanning = true;
    private Handler scanLineHandler;
    private Runnable scanLineRunnable;
    private int scanLineDirection = 1; // 1 for down, -1 for up
    private float scanLinePosition = 0f;

    // 功能开关状态
    private boolean vibrationEnabled = true;
    private boolean soundEnabled = true;
    private Vibrator vibrator;
    private ToneGenerator toneGenerator;

    // 相机状态
    private boolean isFrontCamera = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置全屏模式
        setFullscreenMode();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 请求相机权限
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // 初始化扫描器
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 初始化振动和声音
        initVibrationAndSound();

        // 初始化功能开关
        initFunctionToggles();

        // 初始化底部按钮
        initBottomButtons();
        
        // 初始化扫描线动画
        initScanLineAnimation();
    }

    private void initScanLineAnimation() {
        scanLineHandler = new Handler(Looper.getMainLooper());
        scanLineRunnable = new Runnable() {
            @Override
            public void run() {
                if (isScanning) {
                    // 更新扫描线位置
                    scanLinePosition += 0.02f * scanLineDirection;
                    if (scanLinePosition >= 1f) {
                        scanLineDirection = -1;
                        scanLinePosition = 1f;
                    } else if (scanLinePosition <= 0f) {
                        scanLineDirection = 1;
                        scanLinePosition = 0f;
                    }

                    // 更新扫描线位置
                    updateScanLinePosition();

                    // 继续动画
                    scanLineHandler.postDelayed(this, 50);
                }
            }
        };
        scanLineHandler.post(scanLineRunnable);
    }

    private void updateScanLinePosition() {
        binding.scanLine.setTranslationY(scanLinePosition * 250f);
    }

    private void initVibrationAndSound() {
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
    }

    private void initFunctionToggles() {
        // 设置振动开关
        updateVibrationToggle();
        binding.vibrationToggle.setOnClickListener(v -> {
            vibrationEnabled = !vibrationEnabled;
            updateVibrationToggle();
        });

        // 设置声音开关
        updateSoundToggle();
        binding.soundToggle.setOnClickListener(v -> {
            soundEnabled = !soundEnabled;
            updateSoundToggle();
        });
    }

    private void updateVibrationToggle() {
        binding.vibrationToggle.setSelected(vibrationEnabled);
        binding.vibrationToggle.setImageResource(vibrationEnabled ?
                R.drawable.ic_vibration_on : R.drawable.ic_vibration_off);
    }

    private void updateSoundToggle() {
        binding.soundToggle.setSelected(soundEnabled);
        binding.soundToggle.setImageResource(soundEnabled ?
                R.drawable.ic_sound_on : R.drawable.ic_sound_off);
    }

    private void initBottomButtons() {
        // 切换摄像头按钮
        binding.switchCameraIcon.setOnClickListener(v -> switchCamera());

        // 相册按钮
        binding.albumIcon.setOnClickListener(v -> openAlbum());
    }

    private void switchCamera() {
        isFrontCamera = !isFrontCamera;
        Toast.makeText(this, "切换到" + (isFrontCamera ? "前置" : "后置") + "摄像头", Toast.LENGTH_SHORT).show();
        startCamera();
    }


    private void openAlbum() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
    }

    private void setFullscreenMode() {
        // 隐藏标题栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 设置透明状态栏和导航栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        0
                );
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Android 5.0+ (API 21+)
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, new BarcodeAnalyzer());

                CameraSelector cameraSelector = isFrontCamera ?
                        CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, getString(R.string.camera_start_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) {
                scanImageFromGallery(imageUri);
            }
        }
    }

    private void scanImageFromGallery(Uri imageUri) {
        try {
            // 暂停相机扫描
            isScanning = false;

            // 从相册加载图片
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);

            // 创建InputImage用于ML Kit扫描
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            // 使用ML Kit扫描图片中的二维码
            barcodeScanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty()) {
                            Barcode barcode = barcodes.get(0);
                            if (barcode.getRawValue() != null) {
                                handleBarcodeResult(barcode.getRawValue());
                            } else {
                                Toast.makeText(this, "未在图片中识别到二维码", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(this, "未在图片中识别到二维码", Toast.LENGTH_SHORT).show();
                        }
                        // 恢复相机扫描
                        isScanning = true;
                        scanLineHandler.post(scanLineRunnable);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "图片扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        // 恢复相机扫描
                        isScanning = true;
                        scanLineHandler.post(scanLineRunnable);
                    });

        } catch (Exception e) {
            Toast.makeText(this, "处理图片时出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            // 恢复相机扫描
            isScanning = true;
            scanLineHandler.post(scanLineRunnable);
        }
    }

    private class BarcodeAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

            barcodeScanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty() && isScanning) {
                            Barcode barcode = barcodes.get(0);
                            if (barcode.getRawValue() != null) {
                                handleBarcodeResult(barcode.getRawValue());
                            }
                        }
                    })
                    .addOnCompleteListener(task -> imageProxy.close());
        }
    }

    private void handleBarcodeResult(String result) {
        runOnUiThread(() -> {
            isScanning = false;

            // 振动提示
            if (vibrationEnabled && vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(200);
                }
            }

            // 声音提示
            if (soundEnabled && toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
            }

            // 处理扫描结果
            handleScanResult(result);

            // 1秒后重新开始扫描
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                isScanning = true;
                scanLineHandler.post(scanLineRunnable);
            }, 1000);
        });
    }

    private void handleScanResult(String result) {
        try {
            // 检查是否为有效的URL
            if (URLUtil.isValidUrl(result)) {
                if (isFileUrl(result)) {
                    // 文件链接 - 调用下载
                    downloadFile(result);
                } else {
                    // 网页链接 - 打开浏览器
                    openInBrowser(result);
                }
            } else {
                // 其他内容 - 显示Toast提示
                Toast.makeText(this, "扫描结果: " + result, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "处理扫描结果时出错", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isFileUrl(String url) {
        String lowerUrl = url.toLowerCase();
        // 检查是否为文件下载链接
        return lowerUrl.contains("/download/") ||
                lowerUrl.endsWith(".apk") ||
                lowerUrl.endsWith(".pdf") ||
                lowerUrl.endsWith(".zip") ||
                lowerUrl.endsWith(".rar") ||
                lowerUrl.endsWith(".exe") ||
                lowerUrl.endsWith(".doc") ||
                lowerUrl.endsWith(".docx") ||
                lowerUrl.endsWith(".xls") ||
                lowerUrl.endsWith(".xlsx") ||
                lowerUrl.endsWith(".ppt") ||
                lowerUrl.endsWith(".pptx") ||
                lowerUrl.endsWith(".txt") ||
                lowerUrl.endsWith(".jpg") ||
                lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".png") ||
                lowerUrl.endsWith(".gif") ||
                lowerUrl.endsWith(".mp4") ||
                lowerUrl.endsWith(".mp3") ||
                lowerUrl.endsWith(".avi") ||
                lowerUrl.endsWith(".mkv");
    }

    private void openInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "正在打开网页...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开网页", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadFile(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "正在下载文件...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法下载文件", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (scanLineHandler != null) {
            scanLineHandler.removeCallbacks(scanLineRunnable);
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
    }
}