package com.family.photos.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.family.photos.databinding.ItemPhotoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class PhotoAdapter(
    private val onClick: (Map<String, Any?>) -> Unit
) : ListAdapter<Map<String, Any?>, PhotoAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemPhotoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(photo: Map<String, Any?>) {
            b.tvUploader.text = photo["uploader_name"]?.toString() ?: ""
            val url = photo["file_url"]?.toString() ?: ""
            if (url.isNotEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeStream(URL(url).openStream()) }
                        b.ivPhoto.setImageBitmap(bmp)
                        b.ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                    } catch (_: Exception) {
                        b.ivPhoto.setImageResource(android.R.drawable.ic_menu_gallery)
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
