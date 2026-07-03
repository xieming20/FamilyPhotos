package com.family.photos.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.family.photos.databinding.ItemPhotoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class PhotoAdapter(
    private val onClick: (Map<String, Any?>) -> Unit
) : ListAdapter<Map<String, Any?>, PhotoAdapter.VH>(Diff) {

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.job = null
        holder.b.ivPhoto.setImageDrawable(null)
    }

    inner class VH(val b: ItemPhotoBinding) : RecyclerView.ViewHolder(b.root) {
        var job: Job? = null

        fun bind(photo: Map<String, Any?>) {
            job?.cancel()
            b.tvUploader.text = photo["uploader_name"]?.toString() ?: ""
            val url = photo["file_url"]?.toString() ?: ""
            if (url.isNotEmpty()) {
                val cached = cache.get(url)
                if (cached != null) {
                    b.ivPhoto.setImageBitmap(cached)
                    b.ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                } else {
                    b.ivPhoto.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    b.ivPhoto.setImageResource(android.R.drawable.ic_menu_gallery)
                    val pos = adapterPosition
                    job = CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val bmp = withContext(Dispatchers.IO) {
                                val conn = URL(url).openConnection()
                                conn.connectTimeout = 10000
                                conn.readTimeout = 15000
                                conn.getInputStream().use { input ->
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeStream(input, null, options)
                                    val reqWidth = 300
                                    val reqHeight = 300
                                    var sample = 1
                                    if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                                        val halfH = options.outHeight / 2
                                        val halfW = options.outWidth / 2
                                        while (halfH / sample >= reqHeight && halfW / sample >= reqWidth) sample *= 2
                                    }
                                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
                                    URL(url).openStream().use { decodeInput ->
                                        BitmapFactory.decodeStream(decodeInput, null, decodeOptions)
                                    }
                                }
                            }
                            if (bmp != null && adapterPosition == pos) {
                                cache.put(url, bmp)
                                b.ivPhoto.setImageBitmap(bmp)
                                b.ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            b.root.setOnClickListener { onClick(photo) }
        }
    }

    object Diff : DiffUtil.ItemCallback<Map<String, Any?>>() {
        override fun areItemsTheSame(a: Map<String, Any?>, b: Map<String, Any?>) = a["id"] == b["id"]
        override fun areContentsTheSame(a: Map<String, Any?>, b: Map<String, Any?>) = a["id"] == b["id"]
    }
}
