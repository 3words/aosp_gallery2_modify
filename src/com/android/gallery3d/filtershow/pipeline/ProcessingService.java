/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.gallery3d.filtershow.pipeline;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import com.android.gallery3d.R;
import com.android.gallery3d.filtershow.FilterShowActivity;
import com.android.gallery3d.filtershow.filters.FiltersManager;
import com.android.gallery3d.filtershow.filters.ImageFilter;
import com.android.gallery3d.filtershow.tools.SaveImage;

import java.io.File;

public class ProcessingService extends Service {
    private static final String LOGTAG = "ProcessingService";
    private static final boolean SHOW_IMAGE = false;
    private int mNotificationId;
    private NotificationManager mNotifyMgr = null;
    private Notification.Builder mBuilder = null;

    private static final String PRESET = "preset";
    private static final String SOURCE_URI = "sourceUri";
    private static final String SELECTED_URI = "selectedUri";
    private static final String DESTINATION_FILE = "destinationFile";
    private static final String SAVING = "saving";

    private ProcessingTaskController mProcessingTaskController;
    private ImageSavingTask mImageSavingTask;

    private final IBinder mBinder = new LocalBinder();
    private FilterShowActivity mFiltershowActivity;

    private boolean mSaving = false;
    private boolean mNeedsAlive = false;

    public void setFiltershowActivity(FilterShowActivity filtershowActivity) {
        mFiltershowActivity = filtershowActivity;
    }

    public class LocalBinder extends Binder {
        public ProcessingService getService() {
            return ProcessingService.this;
        }
    }

    public static Intent getSaveIntent(Context context, ImagePreset preset, File destination,
                                        Uri selectedImageUri, Uri sourceImageUri) {
        Intent processIntent = new Intent(context, ProcessingService.class);
        processIntent.putExtra(ProcessingService.SOURCE_URI,
                sourceImageUri.toString());
        processIntent.putExtra(ProcessingService.SELECTED_URI,
                selectedImageUri.toString());
        if (destination != null) {
            processIntent.putExtra(ProcessingService.DESTINATION_FILE, destination.toString());
        }
        processIntent.putExtra(ProcessingService.PRESET,
                preset.getJsonString(context.getString(R.string.saved)));
        processIntent.putExtra(ProcessingService.SAVING, true);
        return processIntent;
    }


    @Override
    public void onCreate() {
        mProcessingTaskController = new ProcessingTaskController(this);
        mImageSavingTask = new ImageSavingTask(this);
        mProcessingTaskController.add(mImageSavingTask);
        setupPipeline();
    }

    @Override
    public void onDestroy() {
        tearDownPipeline();
        mProcessingTaskController.quit();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mNeedsAlive = true;
        if (intent != null && intent.getBooleanExtra(SAVING, false)) {
            // we save using an intent to keep the service around after the
            // activity has been destroyed.
            String presetJson = intent.getStringExtra(PRESET);
            String source = intent.getStringExtra(SOURCE_URI);
            String selected = intent.getStringExtra(SELECTED_URI);
            String destination = intent.getStringExtra(DESTINATION_FILE);
            Uri sourceUri = Uri.parse(source);
            Uri selectedUri = null;
            if (selected != null) {
                selectedUri = Uri.parse(selected);
            }
            File destinationFile = null;
            if (destination != null) {
                destinationFile = new File(destination);
            }
            ImagePreset preset = new ImagePreset();
            preset.readJsonFromString(presetJson);
            mNeedsAlive = false;
            mSaving = true;
            handleSaveRequest(sourceUri, selectedUri, destinationFile, preset);
        }
        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void onStart() {
        mNeedsAlive = true;
        if (!mSaving && mFiltershowActivity != null) {
            mFiltershowActivity.updateUIAfterServiceStarted();
        }
    }

    public void handleSaveRequest(Uri sourceUri, Uri selectedUri,
                                  File destinationFile, ImagePreset preset) {
        mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        mNotificationId++;

        mBuilder =
                new Notification.Builder(this)
                        .setSmallIcon(R.drawable.filtershow_button_fx)
                        .setContentTitle(getString(R.string.filtershow_notification_label))
                        .setContentText(getString(R.string.filtershow_notification_message));

        startForeground(mNotificationId, mBuilder.build());

        updateProgress(SaveImage.MAX_PROCESSING_STEPS, 0);

        // Process the image

        mImageSavingTask.saveImage(sourceUri, selectedUri, destinationFile, preset);
    }

    public void updateNotificationWithBitmap(Bitmap bitmap) {
        mBuilder.setLargeIcon(bitmap);
        mNotifyMgr.notify(mNotificationId, mBuilder.build());
    }

    public void updateProgress(int max, int current) {
        mBuilder.setProgress(max, current, false);
        mNotifyMgr.notify(mNotificationId, mBuilder.build());
    }

    public void completeSaveImage(Uri result) {
        if (SHOW_IMAGE) {
            // TODO: we should update the existing image in Gallery instead
            Intent viewImage = new Intent(Intent.ACTION_VIEW, result);
            viewImage.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(viewImage);
        }
        stopForeground(true);
        stopSelf();
        if (mNeedsAlive) {
            // If the app has been restarted while we were saving...
            mFiltershowActivity.updateUIAfterServiceStarted();
        } else if (mFiltershowActivity.isSimpleEditAction()) {
            // terminate now
            mFiltershowActivity.completeSaveImage(result);
        }
    }

    private void setupPipeline() {
        Resources res = getResources();
        FiltersManager.setResources(res);
        CachingPipeline.createRenderscriptContext(this);

        FiltersManager filtersManager = FiltersManager.getManager();
        filtersManager.addLooks(this);
        filtersManager.addBorders(this);
        filtersManager.addTools(this);
        filtersManager.addEffects();
    }

    private void tearDownPipeline() {
        FilteringPipeline.getPipeline().turnOnPipeline(false);
        FilteringPipeline.reset();
        ImageFilter.resetStatics();
        FiltersManager.getPreviewManager().freeRSFilterScripts();
        FiltersManager.getManager().freeRSFilterScripts();
        FiltersManager.getHighresManager().freeRSFilterScripts();
        FiltersManager.reset();
        CachingPipeline.destroyRenderScriptContext();
    }

    static {
        System.loadLibrary("jni_filtershow_filters");
    }
}
