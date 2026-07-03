package com.family.photos

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.family.photos.adapter.PhotoAdapter
import com.family.photos.databinding.ActivityGalleryBinding
import com.family.photos.util.SupabaseUtil
import kotlinx.coroutines.launch

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var photoAdapter: PhotoAdapter
    private var familyId: String = ""
    private var photos: List<Map<String, Any?>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        familyId = intent.getStringExtra("familyId") ?: ""
        if (familyId.isEmpty()) {
            Toast.makeText(this, "家庭组信息异常", Toast.LENGTH_SHORT).show()
            finish(); overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            return
        }

        setupRecyclerView()
        setupViews()
        loadPhotos()
    }

    private fun setupRecyclerView() {
        photoAdapter = PhotoAdapter { photo ->
            val pos = photos.indexOf(photo)
            startActivity(Intent(this, PhotoDetailActivity::class.java).apply {
                putExtra("photoIndex", if (pos >= 0) pos else 0)
                putExtra("photoCount", photos.size)
            })
            overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out)
            PhotoStore.photos = ArrayList(photos)
        }
        binding.rvPhotos.apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = photoAdapter
        }
    }

    private fun setupViews() {
        binding.toolbar.setNavigationOnClickListener { finish(); overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right) }
        binding.fabUpload.setOnClickListener {
            startActivity(Intent(this, UploadActivity::class.java).apply { putExtra("familyId", familyId) })
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
        binding.swipeRefresh.setOnRefreshListener { loadPhotos() }
    }

    private fun loadPhotos() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                photos = SupabaseUtil.getPhotos(familyId)
                photoAdapter.submitList(photos)
                binding.tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                binding.toolbar.title = "照片画廊（${photos.size}张）"
            } catch (e: Exception) {
                Toast.makeText(this@GalleryActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadPhotos()
    }
}

object PhotoStore {
    var photos: ArrayList<Map<String, Any?>> = arrayListOf()
}
