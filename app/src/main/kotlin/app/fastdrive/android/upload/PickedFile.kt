package app.fastdrive.android.upload

import android.net.Uri

data class PickedFile(val uri: Uri, val name: String, val size: Long, val contentType: String)
