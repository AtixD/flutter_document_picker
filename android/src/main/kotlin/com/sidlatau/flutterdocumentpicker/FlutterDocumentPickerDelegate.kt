package com.sidlatau.flutterdocumentpicker

import android.app.Activity
import android.app.LoaderManager
import android.content.AsyncTaskLoader
import android.content.Context
import android.content.Intent
import android.content.Loader
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import android.app.Application
import android.content.ContentUris
import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.loader.content.CursorLoader
import androidx.core.content.FileProvider
import android.text.TextUtils
import java.io.File

import java.util.ArrayList

private const val REQUEST_CODE_PICK_FILE = 603
private const val EXTRA_URI = "EXTRA_URI"
private const val EXTRA_FILENAME = "EXTRA_FILENAME"
private const val LOADER_FILE_COPY = 603

class FlutterDocumentPickerDelegate(
    private val activity: Activity
) : PluginRegistry.ActivityResultListener, LoaderManager.LoaderCallbacks<String> {
    private var channelResult: MethodChannel.Result? = null
    private var allowedFileExtensions: Array<String>? = null
    private var invalidFileNameSymbols: Array<String>? = null

    fun pickDocument(result: MethodChannel.Result,
                     allowedFileExtensions: Array<String>?,
                     allowedMimeTypes: Array<String>?,
                     invalidFileNameSymbols: Array<String>?
    ) {
        channelResult = result
        this.allowedFileExtensions = allowedFileExtensions
        this.invalidFileNameSymbols = invalidFileNameSymbols

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        if (allowedMimeTypes != null) {
            if (allowedMimeTypes.size == 1) {
                intent.type = allowedMimeTypes.first()
            } else {
                intent.type = "*/*"
                intent.putExtra(Intent.EXTRA_MIME_TYPES, allowedMimeTypes)
            }
        } else {
            intent.type = "*/*"
        }

        activity.startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return when (requestCode) {
            REQUEST_CODE_PICK_FILE -> {
                val params = getFileCopyParams(resultCode, data)
                val channelResult = channelResult
                if (params != null) {
                    var path = RNSharePathUtil.getRealPath(activity.applicationContext, params.uri)
                    channelResult?.success(path)
                } else {
                    channelResult?.success(null)
                }
                return true
            }
            else -> {
                false
            }
        }
    }

    private fun startLoader(params: FileCopyParams) {
        val bundle = Bundle()
        bundle.putParcelable(EXTRA_URI, params.uri)
        bundle.putString(EXTRA_FILENAME, params.fileName)

        val loaderManager = activity.loaderManager
        val loader = loaderManager.getLoader<String>(LOADER_FILE_COPY)
        if (loader == null) {
            loaderManager.initLoader(LOADER_FILE_COPY, bundle, this)
        } else {
            loaderManager.restartLoader(LOADER_FILE_COPY, bundle, this)
        }
    }

    override fun onCreateLoader(id: Int, args: Bundle): Loader<String> {
        val uri = args.getParcelable<Uri>(EXTRA_URI)!!
        val fileName = args.getString(EXTRA_FILENAME)!!
        return FileCopyTaskLoader(activity, uri, fileName)
    }

    override fun onLoadFinished(loader: Loader<String>?, data: String?) {
        channelResult?.success(data)
        activity.loaderManager.destroyLoader(LOADER_FILE_COPY)
    }

    override fun onLoaderReset(loader: Loader<String>?) {
    }

    private fun getFileCopyParams(resultCode: Int, data: Intent?): FileCopyParams? {
        if (resultCode == Activity.RESULT_OK) {
            try {
                val uri = data?.data
                if (uri != null) {
                    val fileName = getFileName(uri)

                    if (fileName != null) {
                        val sanitizedFileName = sanitizeFileName(fileName)

                        return FileCopyParams(
                            uri = uri,
                            fileName = sanitizedFileName,
                            extension = getFileExtension(sanitizedFileName)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(FlutterDocumentPickerPlugin.TAG, "handlePickFileResult", e)
            }
        }
        return null
    }

    private fun sanitizeFileName(fileName: String): String {
        var sanitizedFileName = fileName
        val invalidSymbols = invalidFileNameSymbols
        if (invalidSymbols != null && invalidSymbols.isNotEmpty()) {
            invalidSymbols.forEach {
                sanitizedFileName = sanitizedFileName.replace(it, "_")
            }

        }
        return sanitizedFileName
    }

    private fun getFileExtension(fileName: String): String? {
        val dotIndex = fileName.lastIndexOf(".") + 1
        if (dotIndex > 0 && fileName.length > dotIndex) {
            return fileName.substring(dotIndex)
        }
        return null
    }

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        activity.contentResolver.query(uri, null, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        }
        return fileName
    }
}

data class FileCopyParams(val uri: Uri, val fileName: String, val extension: String?)

class FileCopyTaskLoader(context: Context, private val uri: Uri, private val fileName: String) : AsyncTaskLoader<String>(context) {
    override fun loadInBackground(): String {
        return copyToTemp(uri = uri, fileName = fileName)
    }

    override fun onStartLoading() {
        super.onStartLoading()
        forceLoad()
    }

    private fun copyToTemp(uri: Uri, fileName: String): String {
        val path = context.cacheDir.path + File.separator + fileName

        val file = File(path)

        if (file.exists()) {
            file.delete()
        }

        BufferedInputStream(context.contentResolver.openInputStream(uri)).use { inputStream ->
            BufferedOutputStream(FileOutputStream(file)).use { outputStream ->
                val buf = ByteArray(1024)
                var len = inputStream.read(buf)
                while (len != -1) {
                    outputStream.write(buf, 0, len)
                    len = inputStream.read(buf)
                }
            }
        }

        return file.absolutePath
    }
}

object RNSharePathUtil {
    fun getRealPath(context: Context, fileUri: Uri): String? {
        val realPath: String?
        // SDK < API11
        if (Build.VERSION.SDK_INT < 11) {
            realPath = RNSharePathUtil.getRealPathFromURI_BelowAPI11(context, fileUri)
        } else if (Build.VERSION.SDK_INT < 19) {
            realPath = RNSharePathUtil.getRealPathFromURI_API11to18(context, fileUri)
        } else {
            realPath = RNSharePathUtil.getRealPathFromURI_API19(context, fileUri)
        }// SDK > 19 (Android 4.4) and up
        // SDK >= 11 && SDK < 19
        return realPath
    }


    fun getRealPathFromURI_API11to18(context: Context, contentUri: Uri): String? {
        val proj = arrayOf(MediaStore.Images.Media.DATA)
        var result: String? = null

        val cursorLoader = CursorLoader(context, contentUri, proj, null, null, null)
        val cursor = cursorLoader.loadInBackground()

        if (cursor != null) {
            val column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            result = cursor.getString(column_index)
            cursor.close()
        }
        return result
    }

    fun getRealPathFromURI_BelowAPI11(context: Context, contentUri: Uri): String {
        val proj = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = context.contentResolver.query(contentUri, proj, null, null, null)
        var column_index = 0
        var result = ""
        if (cursor != null) {
            column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            result = cursor.getString(column_index)
            cursor.close()
            return result
        }
        return result
    }

    fun getRealPathFromURI_API19(context: Context, uri: Uri): String? {

        val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                // This is for checking Main Memory
                return if ("primary".equals(type, ignoreCase = true)) {
                    if (split.size > 1) {
                        Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                    } else {
                        Environment.getExternalStorageDirectory().toString() + "/"
                    }
                    // This is for checking SD Card
                } else {
                    "storage" + "/" + docId.replace(":", "/")
                }

            } else if (isDownloadsDocument(uri)) {
                val fileName = getFilePath(context, uri)
                if (fileName != null) {
                    return Environment.getExternalStorageDirectory().toString() + "/Download/" + fileName
                }

                var id = DocumentsContract.getDocumentId(uri)
                if (id.startsWith("raw:")) {
                    id = id.replaceFirst("raw:".toRegex(), "")
                    val file = File(id)
                    if (file.exists())
                        return id
                }

                val contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id))
                return getDataColumn(context, contentUri, null, null)
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val type = split[0]

                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])

                return getDataColumn(context, contentUri, selection, selectionArgs)
            }// MediaProvider
            // DownloadsProvider
        } else if ("content".equals(uri.scheme!!, ignoreCase = true)) {

            // Return the remote address
            return if (isGooglePhotosUri(uri)) uri.lastPathSegment else getDataColumn(context, uri, null, null)

        } else if ("file".equals(uri.scheme!!, ignoreCase = true)) {
            return uri.path
        }// File
        // MediaStore (and general)

        return null
    }

    fun getDataColumn(context: Context, uri: Uri?, selection: String?,
                      selectionArgs: Array<String>?): String? {

        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri!!, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
        return null
    }


    fun getFilePath(context: Context, uri: Uri): String? {

        var cursor: Cursor? = null
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)

        try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                return cursor.getString(index)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

}
