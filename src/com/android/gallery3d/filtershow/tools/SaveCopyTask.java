/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.gallery3d.filtershow.tools;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.util.Log;

import com.android.gallery3d.common.Utils;
import com.android.gallery3d.exif.ExifInterface;
import com.android.gallery3d.filtershow.pipeline.CachingPipeline;
import com.android.gallery3d.filtershow.cache.ImageLoader;
import com.android.gallery3d.filtershow.filters.FiltersManager;
import com.android.gallery3d.filtershow.pipeline.ImagePreset;
import com.android.gallery3d.util.UsageStatistics;
import com.android.gallery3d.util.XmpUtilHelper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * Asynchronous task for saving edited photo as a new copy.
 */
public class SaveCopyTask extends AsyncTask<ImagePreset, Void, Uri> {

    private static final String LOGTAG = "SaveCopyTask";

    /**
     * Callback for the completed asynchronous task.
     */
    public interface Callback {

        void onComplete(Uri result);
    }

    public interface ContentResolverQueryCallback {

        void onCursorResult(Cursor cursor);
    }

    private static final String TIME_STAMP_NAME = "_yyyyMMdd_HHmmss";
    private static final String PREFIX_PANO = "PANO";
    private static final String PREFIX_IMG = "IMG";
    private static final String POSTFIX_JPG = ".jpg";
    private static final String AUX_DIR_NAME = ".aux";

    // When this is true, the source file will be saved into auxiliary directory
    // and hidden from MediaStore. Otherwise, the source will be kept as the
    // same.
    private static final boolean USE_AUX_DIR = true;

    private final Context mContext;
    private final Uri mSourceUri;
    private final Callback mCallback;
    private final File mDestinationFile;
    private final Uri mSelectedImageUri;

    // In order to support the new edit-save behavior such that user won't see
    // the edited image together with the original image, we are adding a new
    // auxiliary directory for the edited image. Basically, the original image
    // will be hidden in that directory after edit and user will see the edited
    // image only.
    // Note that deletion on the edited image will also cause the deletion of
    // the original image under auxiliary directory.
    //
    // There are several situations we need to consider:
    // 1. User edit local image local01.jpg. A local02.jpg will be created in the
    // same directory, and original image will be moved to auxiliary directory as
    // ./.aux/local02.jpg.
    // If user edit the local02.jpg, local03.jpg will be created in the local
    // directory and ./.aux/local02.jpg will be renamed to ./.aux/local03.jpg
    //
    // 2. User edit remote image remote01.jpg from picassa or other server.
    // remoteSavedLocal01.jpg will be saved under proper local directory.
    // In remoteSavedLocal01.jpg, there will be a reference pointing to the
    // remote01.jpg. There will be no local copy of remote01.jpg.
    // If user edit remoteSavedLocal01.jpg, then a new remoteSavedLocal02.jpg
    // will be generated and still pointing to the remote01.jpg
    //
    // 3. User delete any local image local.jpg.
    // Since the filenames are kept consistent in auxiliary directory, every
    // time a local.jpg get deleted, the files in auxiliary directory whose
    // names starting with "local." will be deleted.
    // This pattern will facilitate the multiple images deletion in the auxiliary
    // directory.
    //
    // TODO: Move the saving into a background service.

    /**
     * @param context
     * @param sourceUri The Uri for the original image, which can be the hidden
     *  image under the auxiliary directory or the same as selectedImageUri.
     * @param selectedImageUri The Uri for the image selected by the user.
     *  In most cases, it is a content Uri for local image or remote image.
     * @param destination Destinaton File, if this is null, a new file will be
     *  created under the same directory as selectedImageUri.
     * @param callback Let the caller know the saving has completed.
     * @return the newSourceUri
     */
    public SaveCopyTask(Context context, Uri sourceUri, Uri selectedImageUri,
            File destination, Callback callback)  {
        mContext = context;
        mSourceUri = sourceUri;
        mCallback = callback;
        if (destination == null) {
            mDestinationFile = getNewFile(context, selectedImageUri);
        } else {
            mDestinationFile = destination;
        }

        mSelectedImageUri = selectedImageUri;
    }

    public static File getFinalSaveDirectory(Context context, Uri sourceUri) {
        File saveDirectory = getSaveDirectory(context, sourceUri);
        if ((saveDirectory == null) || !saveDirectory.canWrite()) {
            saveDirectory = new File(Environment.getExternalStorageDirectory(),
                    ImageLoader.DEFAULT_SAVE_DIRECTORY);
        }
        // Create the directory if it doesn't exist
        if (!saveDirectory.exists())
            saveDirectory.mkdirs();
        return saveDirectory;
    }

    public static File getNewFile(Context context, Uri sourceUri) {
        File saveDirectory = getFinalSaveDirectory(context, sourceUri);
        String filename = new SimpleDateFormat(TIME_STAMP_NAME).format(new Date(
                System.currentTimeMillis()));
        if (hasPanoPrefix(context, sourceUri)) {
            return new File(saveDirectory, PREFIX_PANO + filename + POSTFIX_JPG);
        }
        return new File(saveDirectory, PREFIX_IMG + filename + POSTFIX_JPG);
    }

    /**
     * Remove the files in the auxiliary directory whose names are the same as
     * the source image.
     * @param contentResolver The application's contentResolver
     * @param srcContentUri The content Uri for the source image.
     */
    public static void deleteAuxFiles(ContentResolver contentResolver,
            Uri srcContentUri) {
        final String[] fullPath = new String[1];
        String[] queryProjection = new String[] { ImageColumns.DATA };
        querySourceFromContentResolver(contentResolver,
                srcContentUri, queryProjection,
                new ContentResolverQueryCallback() {
                    @Override
                    public void onCursorResult(Cursor cursor) {
                        fullPath[0] = cursor.getString(0);
                    }
                }
        );
        if (fullPath[0] != null) {
            // Construct the auxiliary directory given the source file's path.
            // Then select and delete all the files starting with the same name
            // under the auxiliary directory.
            File currentFile = new File(fullPath[0]);

            String filename = currentFile.getName();
            int firstDotPos = filename.indexOf(".");
            final String filenameNoExt = (firstDotPos == -1) ? filename :
                filename.substring(0, firstDotPos);
            File auxDir = getLocalAuxDirectory(currentFile);
            if (auxDir.exists()) {
                FilenameFilter filter = new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        if (name.startsWith(filenameNoExt + ".")) {
                            return true;
                        } else {
                            return false;
                        }
                    }
                };

                // Delete all auxiliary files whose name is matching the
                // current local image.
                File[] auxFiles = auxDir.listFiles(filter);
                for (File file : auxFiles) {
                    file.delete();
                }
            }
        }
    }

    public Object getPanoramaXMPData(Uri source, ImagePreset preset) {
        Object xmp = null;
        if (preset.isPanoramaSafe()) {
            InputStream is = null;
            try {
                is = mContext.getContentResolver().openInputStream(source);
                xmp = XmpUtilHelper.extractXMPMeta(is);
            } catch (FileNotFoundException e) {
                Log.w(LOGTAG, "Failed to get XMP data from image: ", e);
            } finally {
                Utils.closeSilently(is);
            }
        }
        return xmp;
    }

    public boolean putPanoramaXMPData(File file, Object xmp) {
        if (xmp != null) {
            return XmpUtilHelper.writeXMPMeta(file.getAbsolutePath(), xmp);
        }
        return false;
    }

    public ExifInterface getExifData(Uri source) {
        ExifInterface exif = new ExifInterface();
        String mimeType = mContext.getContentResolver().getType(mSelectedImageUri);
        if (mimeType.equals(ImageLoader.JPEG_MIME_TYPE)) {
            InputStream inStream = null;
            try {
                inStream = mContext.getContentResolver().openInputStream(source);
                exif.readExif(inStream);
            } catch (FileNotFoundException e) {
                Log.w(LOGTAG, "Cannot find file: " + source, e);
            } catch (IOException e) {
                Log.w(LOGTAG, "Cannot read exif for: " + source, e);
            } finally {
                Utils.closeSilently(inStream);
            }
        }
        return exif;
    }

    public boolean putExifData(File file, ExifInterface exif, Bitmap image) {
        boolean ret = false;
        try {
            exif.writeExif(image, file.getAbsolutePath());
            ret = true;
        } catch (FileNotFoundException e) {
            Log.w(LOGTAG, "File not found: " + file.getAbsolutePath(), e);
        } catch (IOException e) {
            Log.w(LOGTAG, "Could not write exif: ", e);
        }
        return ret;
    }

    /**
     * The task should be executed with one given bitmap to be saved.
     */
    @Override
    protected Uri doInBackground(ImagePreset... params) {
        // TODO: Support larger dimensions for photo saving.
        if (params[0] == null || mSourceUri == null || mSelectedImageUri == null) {
            return null;
        }

        ImagePreset preset = params[0];
        Uri uri = null;
        if (!preset.hasModifications()) {
            // This can happen only when preset has no modification but save
            // button is enabled, it means the file is loaded with filters in
            // the XMP, then all the filters are removed or restore to default.
            // In this case, when mSourceUri exists, rename it to the
            // destination file.
            File srcFile = getLocalFileFromUri(mContext, mSourceUri);
            // If the source is not a local file, then skip this renaming and
            // create a local copy as usual.
            if (srcFile != null) {
                srcFile.renameTo(mDestinationFile);
                uri = insertContent(mContext, mSelectedImageUri, mDestinationFile,
                        System.currentTimeMillis());
                removeSelectedImage();
                return uri;
            }
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        boolean noBitmap = true;
        int num_tries = 0;

        // If necessary, move the source file into the auxiliary directory,
        // newSourceUri is then pointing to the new location.
        // If no file is moved, newSourceUri will be the same as mSourceUri.
        Uri newSourceUri;
        if (USE_AUX_DIR) {
            newSourceUri = moveSrcToAuxIfNeeded(mSourceUri, mDestinationFile);
        } else {
            newSourceUri = mSourceUri;
        }
        // Stopgap fix for low-memory devices.
        while (noBitmap) {
            try {
                // Try to do bitmap operations, downsample if low-memory
                Bitmap bitmap = ImageLoader.loadMutableBitmap(mContext, newSourceUri, options);
                if (bitmap == null) {
                    return null;
                }
                CachingPipeline pipeline = new CachingPipeline(FiltersManager.getManager(), "Saving");
                bitmap = pipeline.renderFinalImage(bitmap, preset);

                Object xmp = getPanoramaXMPData(mSelectedImageUri, preset);
                ExifInterface exif = getExifData(mSelectedImageUri);

                // Set tags
                long time = System.currentTimeMillis();
                exif.addDateTimeStampTag(ExifInterface.TAG_DATE_TIME, time,
                        TimeZone.getDefault());
                exif.setTag(exif.buildTag(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.Orientation.TOP_LEFT));

                // Remove old thumbnail
                exif.removeCompressedThumbnail();

                // If we succeed in writing the bitmap as a jpeg, return a uri.
                if (putExifData(mDestinationFile, exif, bitmap)) {
                    putPanoramaXMPData(mDestinationFile, xmp);
                    uri = insertContent(mContext, mSelectedImageUri, mDestinationFile,
                            time);
                }

                // mDestinationFile will save the newSourceUri info in the XMP.
                XmpPresets.writeFilterXMP(mContext, newSourceUri, mDestinationFile, preset);

                // Since we have a new image inserted to media store, we can
                // safely remove the old one which is selected by the user.
                if (USE_AUX_DIR) {
                    removeSelectedImage();
                }
                noBitmap = false;
                UsageStatistics.onEvent(UsageStatistics.COMPONENT_EDITOR,
                        "SaveComplete", null);
            } catch (java.lang.OutOfMemoryError e) {
                // Try 5 times before failing for good.
                if (++num_tries >= 5) {
                    throw e;
                }
                System.gc();
                options.inSampleSize *= 2;
            }
        }
        return uri;
    }

    private void removeSelectedImage() {
        String scheme = mSelectedImageUri.getScheme();
        if (scheme != null && scheme.equals(ContentResolver.SCHEME_CONTENT)) {
            if (mSelectedImageUri.getAuthority().equals(MediaStore.AUTHORITY)) {
                mContext.getContentResolver().delete(mSelectedImageUri, null, null);
            }
        }
    }

    /**
     *  Move the source file to auxiliary directory if needed and return the Uri
     *  pointing to this new source file.
     * @param srcUri Uri to the source image.
     * @param dstFile Providing the destination file info to help to build the
     *  auxiliary directory and new source file's name.
     * @return the newSourceUri pointing to the new source image.
     */
    private Uri moveSrcToAuxIfNeeded(Uri srcUri, File dstFile) {
        File srcFile = getLocalFileFromUri(mContext, srcUri);
        if (srcFile == null) {
            Log.d(LOGTAG, "Source file is not a local file, no update.");
            return srcUri;
        }

        // Get the destination directory and create the auxilliary directory
        // if necessary.
        File auxDiretory = getLocalAuxDirectory(dstFile);
        if (!auxDiretory.exists()) {
            auxDiretory.mkdirs();
        }

        // Make sure there is a .nomedia file in the auxiliary directory, such
        // that MediaScanner will not report those files under this directory.
        File noMedia = new File(auxDiretory, ".nomedia");
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile();
            } catch (IOException e) {
                Log.e(LOGTAG, "Can't create the nomedia");
                return srcUri;
            }
        }
        // We are using the destination file name such that photos sitting in
        // the auxiliary directory are matching the parent directory.
        File newSrcFile = new File(auxDiretory, dstFile.getName());

        if (!newSrcFile.exists()) {
            srcFile.renameTo(newSrcFile);
        }

        return Uri.fromFile(newSrcFile);

    }

    private static File getLocalAuxDirectory(File dstFile) {
        File dstDirectory = dstFile.getParentFile();
        File auxDiretory = new File(dstDirectory + "/" + AUX_DIR_NAME);
        return auxDiretory;
    }

    @Override
    protected void onPostExecute(Uri result) {
        if (mCallback != null) {
            mCallback.onComplete(result);
        }
    }

    private static void querySource(Context context, Uri sourceUri, String[] projection,
            ContentResolverQueryCallback callback) {
        ContentResolver contentResolver = context.getContentResolver();
        querySourceFromContentResolver(contentResolver, sourceUri, projection, callback);
    }

    private static void querySourceFromContentResolver(
            ContentResolver contentResolver, Uri sourceUri, String[] projection,
            ContentResolverQueryCallback callback) {
        Cursor cursor = null;
        try {
            cursor = contentResolver.query(sourceUri, projection, null, null,
                    null);
            if ((cursor != null) && cursor.moveToNext()) {
                callback.onCursorResult(cursor);
            }
        } catch (Exception e) {
            // Ignore error for lacking the data column from the source.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static File getSaveDirectory(Context context, Uri sourceUri) {
        File file = getLocalFileFromUri(context, sourceUri);
        if (file != null) {
            return file.getParentFile();
        } else {
            return null;
        }
    }

    /**
     * Construct a File object based on the srcUri.
     * @return The file object. Return null if srcUri is invalid or not a local
     * file.
     */
    private static File getLocalFileFromUri(Context context, Uri srcUri) {
        if (srcUri == null) {
            Log.e(LOGTAG, "srcUri is null.");
            return null;
        }

        String scheme = srcUri.getScheme();
        if (scheme == null) {
            Log.e(LOGTAG, "scheme is null.");
            return null;
        }

        final File[] file = new File[1];
        // sourceUri can be a file path or a content Uri, it need to be handled
        // differently.
        if (scheme.equals(ContentResolver.SCHEME_CONTENT)) {
            if (srcUri.getAuthority().equals(MediaStore.AUTHORITY)) {
                querySource(context, srcUri, new String[] {
                        ImageColumns.DATA
                },
                        new ContentResolverQueryCallback() {

                            @Override
                            public void onCursorResult(Cursor cursor) {
                                file[0] = new File(cursor.getString(0));
                            }
                        });
            }
        } else if (scheme.equals(ContentResolver.SCHEME_FILE)) {
            file[0] = new File(srcUri.getPath());
        }
        return file[0];
    }

    /**
     * Gets the actual filename for a Uri from Gallery's ContentProvider.
     */
    private static String getTrueFilename(Context context, Uri src) {
        if (context == null || src == null) {
            return null;
        }
        final String[] trueName = new String[1];
        querySource(context, src, new String[] {
                ImageColumns.DATA
        }, new ContentResolverQueryCallback() {
            @Override
            public void onCursorResult(Cursor cursor) {
                trueName[0] = new File(cursor.getString(0)).getName();
            }
        });
        return trueName[0];
    }

    /**
     * Checks whether the true filename has the panorama image prefix.
     */
    private static boolean hasPanoPrefix(Context context, Uri src) {
        String name = getTrueFilename(context, src);
        return name != null && name.startsWith(PREFIX_PANO);
    }

    /**
     * Insert the content (saved file) with proper source photo properties.
     */
    private static Uri insertContent(Context context, Uri sourceUri, File file,
            long time) {
        time /= 1000;

        final ContentValues values = new ContentValues();
        values.put(Images.Media.TITLE, file.getName());
        values.put(Images.Media.DISPLAY_NAME, file.getName());
        values.put(Images.Media.MIME_TYPE, "image/jpeg");
        values.put(Images.Media.DATE_TAKEN, time);
        values.put(Images.Media.DATE_MODIFIED, System.currentTimeMillis());
        values.put(Images.Media.DATE_ADDED, time);
        values.put(Images.Media.ORIENTATION, 0);
        values.put(Images.Media.DATA, file.getAbsolutePath());
        values.put(Images.Media.SIZE, file.length());

        final String[] projection = new String[] {
                ImageColumns.DATE_TAKEN,
                ImageColumns.LATITUDE, ImageColumns.LONGITUDE,
        };
        querySource(context, sourceUri, projection,
                new ContentResolverQueryCallback() {

                    @Override
                    public void onCursorResult(Cursor cursor) {
                        values.put(Images.Media.DATE_TAKEN, cursor.getLong(0));

                        double latitude = cursor.getDouble(1);
                        double longitude = cursor.getDouble(2);
                        // TODO: Change || to && after the default location
                        // issue is fixed.
                        if ((latitude != 0f) || (longitude != 0f)) {
                            values.put(Images.Media.LATITUDE, latitude);
                            values.put(Images.Media.LONGITUDE, longitude);
                        }
                    }
                });

        return context.getContentResolver().insert(
                Images.Media.EXTERNAL_CONTENT_URI, values);
    }

}
