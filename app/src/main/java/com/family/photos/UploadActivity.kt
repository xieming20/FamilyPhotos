package com.family.photos

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.photos.databinding.ActivityUploadBinding
import com.family.photos.util.SupabaseUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class UploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUploadBinding
    private var familyId: String = ""
    private val allUris = mutableListOf<Uri>()
    private val selectedUris = mutableSetOf<Uri>()
    private lateinit var adapter: PhotoSelectAdapter

    companion object {
        private const val REQUEST_PICK = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        familyId = intent.getStringExtra("familyId") ?: ""
        if (familyId.isEmpty()) { Toast.makeText(this, "家庭组信息异常", Toast.LENGTH_SHORT).show(); finish(); overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right); return }

        binding.toolbar.setNavigationOnClickListener { finish(); overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right) }

        adapter = PhotoSelectAdapter(allUris, selectedUris) { uri, isSelected ->
            if (isSelected) selectedUris.add(uri) else selectedUris.remove(uri)
            updateCount()
        }

        binding.rvSelectedPhotos.apply {
            layoutManager = GridLayoutManager(this@UploadActivity, 3)
            adapter = this@UploadActivity.adapter
            isNestedScrollingEnabled = false
        }

        binding.btnSelectMulti.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, REQUEST_PICK
            )
        }

        binding.btnUpload.setOnClickListener { uploadSelected() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != Activity.RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            ?: data.data?.let { uris.add(it) }

        for (uri in uris) {
            if (!allUris.contains(uri)) {
                allUris.add(uri)
                selectedUris.add(uri)
            }
        }
        adapter.notifyDataSetChanged()
        updateCount()
    }

    private fun updateCount() {
        binding.tvSelectedCount.text = if (selectedUris.isEmpty()) "未选择照片"
            else "已选择 ${selectedUris.size} 张照片（共 ${allUris.size} 张）"
    }

    private fun uploadSelected() {
        if (selectedUris.isEmpty()) { Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show(); return }
        binding.btnUpload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        val uris = selectedUris.toList()
        var success = 0
        var fail = 0
        var skipped = 0

        lifecycleScope.launch {
            binding.tvProgress.text = "正在检查重复照片…"
            binding.tvProgress.visibility = View.VISIBLE
            val existingUrls = SupabaseUtil.getExistingPhotoUrls(familyId)

            for ((i, uri) in uris.withIndex()) {
                binding.tvProgress.text = "正在上传 ${i + 1}/${uris.size}"
                try {
                    val file = copyUriToFile(uri) ?: throw Exception("读取失败")
                    val md5 = md5OfFile(file)
                    val expectedUrl = "https://plobrqfaqtcihzzakmbk.supabase.co/storage/v1/object/public/photos/photo_${md5}.jpg"
                    if (md5.isNotEmpty() && existingUrls.contains(expectedUrl)) {
                        skipped++
                        continue
                    }
                    val displayName = try {
                        SupabaseUtil.getUserProfile(SupabaseUtil.currentUserId())?.get("display_name")?.toString() ?: "用户"
                    } catch (_: Exception) { "用户" }
                    SupabaseUtil.uploadPhoto(familyId, file, binding.etDescription.text.toString().trim(), SupabaseUtil.currentUserId(), displayName, md5)
                    success++
                } catch (_: Exception) { fail++ }
            }
            binding.progressBar.visibility = View.GONE
            binding.tvProgress.visibility = View.GONE
            binding.btnUpload.isEnabled = true
            val msg = buildString {
                append("上传完成：成功 $success 张")
                if (fail > 0) append("，失败 $fail 张")
                if (skipped > 0) append("，跳过重复 $skipped 张")
            }
            Toast.makeText(this@UploadActivity, msg, Toast.LENGTH_LONG).show()
            if (success > 0) { finish(); overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right) }
        }
    }

    private suspend fun copyUriToFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            tempFile
        } catch (_: Exception) { null }
    }

    private fun md5OfFile(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }

    class PhotoSelectAdapter(
        private val uris: List<Uri>,
        private val selected: Set<Uri>,
        private val onToggle: (Uri, Boolean) -> Unit
    ) : RecyclerView.Adapter<PhotoSelectAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
            val circleOuter: View = view.findViewById(R.id.circleOuter)
            val circleInner: View = view.findViewById(R.id.circleInner)
            val checkArea: View = view.findViewById(R.id.checkArea)
            val mask: View = view.findViewById(R.id.mask)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo_select, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = uris[position]
            holder.ivThumb.setImageURI(uri)
            val isChecked = selected.contains(uri)
            holder.circleInner.visibility = if (isChecked) View.VISIBLE else View.GONE
            holder.circleOuter.visibility = if (isChecked) View.GONE else View.VISIBLE
            holder.mask.setBackgroundColor(if (isChecked) 0x00000000 else 0x4D000000)

            holder.checkArea.setOnClickListener {
                val nowChecked = !selected.contains(uri)
                onToggle(uri, nowChecked)
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }
        }

        override fun getItemCount() = uris.size
    }
}
