package androidx.compose.ui.graphics

import android.graphics.Bitmap

/** Android Bitmap.asImageBitmap() 的桌面平替（NmIcon 角标用） */
fun Bitmap.asImageBitmap(): ImageBitmap = img.toComposeImageBitmap()
