package com.family.photos

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.family.photos.databinding.ActivityPhotoDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentIndex = intent.getIntExtra("photoIndex", 0)
        val count = intent.getIntExtra("photoCount", 1)

        binding.toolbar.setNavigationOnClickListener { finish(); overridePendingTransition(R.anim.fade_out, R.anim.fade_in) }
        updateTitle(currentIndex, count)

        val adapter = PhotoPagerAdapter()
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentIndex, false)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateTitle(position, count)
            }
        })

        binding.fabDownload.setOnClickListener { downloadCurrentPhoto() }
        binding.fabShare.setOnClickListener { shareCurrentPhoto() }
    }

    private fun updateTitle(position: Int, count: Int) {
        binding.toolbar.title = "${position + 1} / $count"
    }

    private fun getCurrentPhoto(): Map<String, Any?>? {
        return PhotoStore.photos.getOrNull(currentIndex)
    }

    private fun downloadCurrentPhoto() {
        val photo = getCurrentPhoto() ?: return
        val iv = getCurrentImageView() ?: return
        val bitmap = (iv as? TouchImageView)?.let {
            try { it.drawingCache } catch (_: Exception) { null }
        }
        if (bitmap == null) {
            lifecycleScope.launch {
                try {
                    val url = photo["file_url"]?.toString() ?: return@launch
                    val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeStream(URL(url).openStream()) } ?: return@launch
                    saveBitmapToGallery(bmp)
                } catch (e: Exception) {
                    Toast.makeText(this@PhotoDetailActivity, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            saveBitmapToGallery(bitmap)
        }
    }

    private fun saveBitmapToGallery(bitmap: android.graphics.Bitmap) {
        val fileName = "family_photo_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/时光相册")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            var out: OutputStream? = null
            try {
                out = contentResolver.openOutputStream(it)!!
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(it, values, null, null)
                }
                Toast.makeText(this, "照片已保存到相册", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally { out?.close() }
        }
    }

    private fun shareCurrentPhoto() {
        val photo = getCurrentPhoto() ?: return
        val fileUrl = photo["file_url"]?.toString() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "来看看我们家这张照片！$fileUrl")
        }, "分享照片"))
    }

    private fun getCurrentImageView(): View? {
        val rv = binding.viewPager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(currentIndex) ?: return null
        return holder.itemView.findViewById(R.id.ivPhoto)
    }

    inner class PhotoPagerAdapter : RecyclerView.Adapter<PhotoPagerAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo_viewer, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val photo = PhotoStore.photos.getOrNull(position)
            val iv = holder.itemView.findViewById<TouchImageView>(R.id.ivPhoto)
            val tvInfo = holder.itemView.findViewById<android.widget.TextView>(R.id.tvPhotoInfo)
            val url = photo?.get("file_url")?.toString() ?: ""
            val uploader = photo?.get("uploader_name")?.toString() ?: ""
            val time = (photo?.get("upload_time") as? Number)?.toLong() ?: 0L
            val desc = photo?.get("description")?.toString() ?: ""
            val timeStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA).format(Date(time))

            tvInfo.text = buildString {
                append(uploader)
                append(" · ")
                append(timeStr)
                if (desc.isNotEmpty()) {
                    append("\n")
                    append(desc)
                }
            }

            iv.setImageResource(android.R.drawable.ic_menu_gallery)
            if (url.isNotEmpty()) {
                lifecycleScope.launch {
                    try {
                        val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeStream(URL(url).openStream()) }
                        if (bmp != null) iv.setImageBitmap(bmp)
                    } catch (_: Exception) {}
                }
            }
        }

        override fun getItemCount() = PhotoStore.photos.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view)
    }
}
