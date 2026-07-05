package com.family.photos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.family.photos.databinding.ActivityMainBinding
import com.family.photos.util.SupabaseUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var familyGroups: List<Map<String, Any?>> = emptyList()
    private var currentIndex: Int = -1
    private val currentFamilyGroup: Map<String, Any?>? get() = familyGroups.getOrNull(currentIndex)
    private val familyId: String get() = currentFamilyGroup?.get("id")?.toString() ?: ""
    private val isAdmin: Boolean get() = currentFamilyGroup?.get("creator_id")?.toString() == SupabaseUtil.currentUserId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!SupabaseUtil.isSignedIn()) {
            navigateToLogin(animate = false)
            return
        }

        playEnterAnimations()
        loadUserProfile()
        setupViews()
        checkForUpdate()

        val intentFamilyId = intent.getStringExtra("family_id")
        val intentFamilyName = intent.getStringExtra("family_name")
        if (!intentFamilyId.isNullOrEmpty()) {
            familyGroups = listOf(mapOf(
                "id" to intentFamilyId,
                "name" to (intentFamilyName ?: ""),
                "creator_id" to "",
                "member_ids" to listOf(SupabaseUtil.currentUserId()),
                "member_names" to listOf("")
            ))
            currentIndex = 0
            updateFamilyDisplay()
        }
    }

    private fun playEnterAnimations() {
        val views = listOf(binding.cardGallery, binding.cardUpload, binding.cardFamily, binding.cardFamilySelector)
        views.forEachIndexed { i, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate().alpha(1f).translationY(0f)
                .setDuration(400).setInterpolator(DecelerateInterpolator())
                .setStartDelay((i * 80 + 100).toLong()).start()
        }
    }

    private fun setupViews() {
        binding.cardGallery.setOnClickListener {
            if (familyId.isEmpty()) showNoFamilyTip()
            else { startActivity(Intent(this, GalleryActivity::class.java).apply { putExtra("familyId", familyId) }); overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left) }
        }

        binding.cardUpload.setOnClickListener {
            if (familyId.isEmpty()) showNoFamilyTip()
            else { startActivity(Intent(this, UploadActivity::class.java).apply { putExtra("familyId", familyId) }); overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left) }
        }

        binding.cardFamily.setOnClickListener { showFamilyDialog() }

        binding.familySelectorArea.setOnClickListener {
            if (familyGroups.size > 1) showFamilySwitcherDialog()
            else if (familyGroups.isNotEmpty()) showFamilyInfo()
            else showFamilyDialog()
        }

        binding.btnLogout.setOnClickListener {
            showSmoothDialog("退出登录", "确定要退出当前账号吗？", "确定") {
                SupabaseUtil.signOut()
                navigateToLogin(animate = true)
            }
        }

        binding.btnCheckUpdate.setOnClickListener { checkForUpdateManual() }
        binding.btnCheckUpdate.text = versionLabel
        binding.btnShareApp.setOnClickListener { shareApp() }
        binding.btnStorageUsage.setOnClickListener { showStorageUsage() }
    }

    private fun loadUserProfile() {
        val uid = SupabaseUtil.currentUserId()
        if (uid.isEmpty()) return
        lifecycleScope.launch {
            try {
                SupabaseUtil.updateHeartbeat(uid)
            } catch (_: Exception) {}
            try {
                val profile = SupabaseUtil.getUserProfile(uid)
                if (profile != null) {
                    binding.tvWelcome.text = "欢迎，${profile["display_name"]}"
                }
            } catch (_: Exception) {
                binding.tvWelcome.text = "欢迎，新用户"
            }
            loadMyFamilyGroups()
        }
    }

    private fun loadMyFamilyGroups() {
        val uid = SupabaseUtil.currentUserId()
        if (uid.isEmpty()) return
        lifecycleScope.launch {
            try {
                familyGroups = SupabaseUtil.getMyFamilyGroups(uid)
                if (familyGroups.isNotEmpty()) {
                    currentIndex = 0
                    updateFamilyDisplay()
                } else {
                    currentIndex = -1
                    updateFamilyDisplay()
                }
            } catch (_: Exception) {
                updateFamilyDisplay()
            }
        }
    }

    private fun updateFamilyDisplay() {
        val group = currentFamilyGroup
        if (group == null) {
            binding.tvFamilyName.text = "请创建或加入家庭组"
            binding.tvFamilyMemberCount.text = ""
            binding.tvAdminBadge.visibility = View.GONE
            binding.ivDropdown.visibility = View.GONE
            return
        }

        @Suppress("UNCHECKED_CAST")
        val names = (group["member_names"] as? List<String>) ?: emptyList()
        val isCreator = group["creator_id"]?.toString() == SupabaseUtil.currentUserId()

        binding.tvFamilyName.text = group["name"]?.toString() ?: ""
        binding.tvFamilyMemberCount.text = "${names.size}位成员"
        binding.tvAdminBadge.visibility = if (isCreator) View.VISIBLE else View.GONE
        binding.ivDropdown.visibility = View.VISIBLE
        loadPhotoCount(familyId)
    }

    private fun loadPhotoCount(fid: String) {
        lifecycleScope.launch {
            try {
                val count = SupabaseUtil.getPhotos(fid).size
                binding.tvGalleryCount.text = if (count > 0) "${count}张照片" else "浏览家人分享的照片"
            } catch (_: Exception) {
                binding.tvGalleryCount.text = "浏览家人分享的照片"
            }
        }
    }

    private fun checkForUpdate() {
        lifecycleScope.launch {
            try {
                val versionCode = packageManager.getPackageInfo(packageName, 0).let {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") it.versionCode
                }
                val update = SupabaseUtil.checkForUpdate(versionCode) ?: return@launch
                showUpdateDialog(update)
            } catch (_: Exception) {}
        }
    }

    private val versionLabel: String by lazy {
        "检查更新（v${packageManager.getPackageInfo(packageName, 0).versionName}）"
    }

    private fun checkForUpdateManual() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "检查中..."
        lifecycleScope.launch {
            try {
                val versionCode = packageManager.getPackageInfo(packageName, 0).let {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode.toInt()
                    else @Suppress("DEPRECATION") it.versionCode
                }
                val update = SupabaseUtil.checkForUpdate(versionCode)
                if (update != null) {
                    showUpdateDialog(update)
                } else {
                    Toast.makeText(this@MainActivity, "当前已是最新版本（v$versionCode）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "检查失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = versionLabel
            }
        }
    }

    private fun showUpdateDialog(update: Map<String, String>) {
        val dialog = AlertDialog.Builder(this@MainActivity)
            .setTitle("发现新版本 ${update["version_name"]}")
            .setMessage(update["release_notes"] ?: "有新版本可用，建议更新")
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall(update["download_url"] ?: "")
            }
            .setNegativeButton("稍后再说", null)
            .create()
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun downloadAndInstall(url: String) {
        if (url.isEmpty()) {
            Toast.makeText(this, "下载链接暂不可用", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(url).build()
                    val response = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .build().newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("下载失败")
                    val body = response.body ?: throw Exception("下载失败")
                    val contentLength = body.contentLength()
                    val file = File(cacheDir, "时光相册-update.apk")
                    var downloaded = 0L
                    var lastProgress = -1
                    file.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var bytes = input.read(buffer)
                            while (bytes != -1) {
                                out.write(buffer, 0, bytes)
                                downloaded += bytes
                                if (contentLength > 0) {
                                    val progress = (downloaded * 100 / contentLength).toInt()
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            binding.btnCheckUpdate.text = "下载中 $progress%"
                                        }
                                    }
                                }
                                bytes = input.read(buffer)
                            }
                        }
                    }
                    file
                }
                binding.btnCheckUpdate.text = "安装中..."
                val uri = FileProvider.getUriForFile(
                    this@MainActivity, "${packageName}.fileprovider", apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "检查更新"
            }
        }
    }

    private fun shareApp() {
        binding.btnShareApp.isEnabled = false
        lifecycleScope.launch {
            try {
                val info = SupabaseUtil.getLatestVersionInfo()
                val downloadUrl = info?.get("download_url")
                if (downloadUrl.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, "暂无可用下载链接，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val versionName = info["version_name"] ?: ""
                val shareText = buildString {
                    append("快来下载「时光相册」！\n")
                    if (versionName.isNotEmpty()) append("当前版本：$versionName\n")
                    append("和家人一起分享老照片的温馨回忆 📷\n\n")
                    append("下载链接：$downloadUrl")
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "推荐应用：时光相册")
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(Intent.createChooser(shareIntent, "分享应用给朋友"))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "获取下载链接失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnShareApp.isEnabled = true
            }
        }
    }

    private fun showFamilySwitcherDialog() {
        val uid = SupabaseUtil.currentUserId()
        val dialogView = layoutInflater.inflate(android.R.layout.select_dialog_singlechoice, null)
        val listView = android.widget.ListView(this).apply {
            choiceMode = android.widget.AbsListView.CHOICE_MODE_SINGLE
            dividerHeight = 0
        }

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = familyGroups.size
            override fun getItem(position: Int) = familyGroups[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_family_switcher, parent, false)
                val group = familyGroups[position]
                @Suppress("UNCHECKED_CAST")
                val names = (group["member_names"] as? List<String>) ?: emptyList()
                val isCreator = group["creator_id"]?.toString() == uid
                val badge = if (isCreator) " [管理员]" else ""
                view.findViewById<TextView>(R.id.tvGroupName)?.text = "${group["name"]}$badge（${names.size}人）"
                view.findViewById<ImageView>(R.id.ivDelete)?.setOnClickListener {
                    confirmDeleteFamily(position)
                }
                view.setOnClickListener {
                    currentIndex = position
                    updateFamilyDisplay()
                    alertDialog?.dismiss()
                }
                if (position == currentIndex) {
                    view.setBackgroundColor(0x1A3B7DD8)
                } else {
                    view.setBackgroundColor(0x00000000)
                }
                return view
            }
        }
        listView.adapter = adapter

        val alertDialog = AlertDialog.Builder(this)
            .setTitle("切换家庭组")
            .setView(listView)
            .setNegativeButton("关闭", null)
            .create()
        this.alertDialog = alertDialog
        alertDialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        alertDialog.show()
    }

    private var alertDialog: AlertDialog? = null

    private fun confirmDeleteFamily(position: Int) {
        val group = familyGroups.getOrNull(position) ?: return
        val uid = SupabaseUtil.currentUserId()
        val isCreator = group["creator_id"]?.toString() == uid
        val msg = if (isCreator) "确定要删除「${group["name"]}」吗？\n此操作不可恢复，所有照片将被删除。"
                  else "确定要退出「${group["name"]}」吗？"
        showSmoothDialog(if (isCreator) "删除家庭组" else "退出家庭组", msg, "确定") {
            val fid = group["id"].toString()
            lifecycleScope.launch {
                try {
                    if (isCreator) SupabaseUtil.deleteFamilyGroup(fid)
                    familyGroups = familyGroups.filter { it["id"]?.toString() != fid }
                    currentIndex = if (familyGroups.isEmpty()) -1 else 0
                    updateFamilyDisplay()
                    alertDialog?.dismiss()
                    Toast.makeText(this@MainActivity, if (isCreator) "家庭组已删除" else "已退出家庭组", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "操作失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showNoFamilyTip() {
        Toast.makeText(this, "请先创建或加入一个家庭组", Toast.LENGTH_SHORT).show()
        showFamilyDialog()
    }

    private fun showFamilyDialog() {
        val items = if (familyId.isEmpty()) {
            listOf(
                Triple("创建家庭组", android.R.drawable.ic_menu_add, 0),
                Triple("加入家庭组", android.R.drawable.ic_menu_send, 1)
            )
        } else {
            val base = mutableListOf(
                Triple("查看家庭信息", android.R.drawable.ic_menu_info_details, 0),
                Triple("邀请家人加入", android.R.drawable.ic_menu_share, 1),
                Triple("切换家庭组", android.R.drawable.ic_menu_set_as, 7),
                Triple("创建新家庭组", android.R.drawable.ic_menu_add, 2),
                Triple("加入其他家庭组", android.R.drawable.ic_menu_send, 3)
            )
            if (isAdmin) {
                base.add(Triple("成员管理", android.R.drawable.ic_menu_view, 5))
                base.add(Triple("修改家庭组信息", android.R.drawable.ic_menu_edit, 6))
                base.add(Triple("删除此家庭组", android.R.drawable.ic_menu_delete, 4))
            }
            base.toList()
        }

        val labels = items.map { it.first }.toMutableList()
        val icons = items.map { it.second }

        val listAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = labels.size
            override fun getItem(position: Int) = labels[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val view = convertView ?: layoutInflater.inflate(android.R.layout.select_dialog_item, parent, false)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                tv.text = labels[position]
                tv.textSize = 14f
                if (position < icons.size) {
                    tv.setCompoundDrawablesWithIntrinsicBounds(icons[position], 0, 0, 0)
                    tv.compoundDrawablePadding = 24
                }
                return view
            }
        }

        val listView = android.widget.ListView(this).apply {
            adapter = listAdapter
            dividerHeight = 0
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("家庭组管理")
            .setView(listView)
            .setNegativeButton("关闭", null)
            .create()

        listView.setOnItemClickListener { _, _, which, _ ->
            val action = items[which].third
            if (familyId.isEmpty()) {
                if (action == 0) showCreateFamilyDialog() else showJoinFamilyDialog()
            } else {
                when (action) {
                    0 -> showFamilyInfo()
                    1 -> showInviteCode()
                    2 -> showCreateFamilyDialog()
                    3 -> showJoinFamilyDialog()
                    4 -> showDeleteFamilyDialog()
                    5 -> showMemberManage()
                    6 -> showEditFamilyInfo()
                    7 -> if (familyGroups.size > 1) showFamilySwitcherDialog() else Toast.makeText(this, "当前只在一个家庭组中", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun showDeleteFamilyDialog() {
        currentFamilyGroup?.let { group ->
            showSmoothDialog("删除家庭组", "确定要删除「${group["name"]}」吗？\n此操作不可恢复，所有照片将被删除。", "删除") {
                val fid = group["id"].toString()
                lifecycleScope.launch {
                    try {
                        SupabaseUtil.deleteFamilyGroup(fid)
                        familyGroups = familyGroups.filter { it["id"]?.toString() != fid }
                        currentIndex = if (familyGroups.isEmpty()) -1 else 0
                        updateFamilyDisplay()
                        Toast.makeText(this@MainActivity, "家庭组已删除", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showCreateFamilyDialog() {
        val input = EditText(this).apply { hint = "请输入家庭组名称"; setPadding(48, 24, 48, 24) }
        showSmoothInputDialog("创建家庭组", input, "创建") {
            val name = input.text.toString().trim()
            if (name.isEmpty()) { Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show(); return@showSmoothInputDialog }
            lifecycleScope.launch {
                try {
                    SupabaseUtil.createFamilyGroup(name, SupabaseUtil.currentUserId(), getDisplayName())
                    loadMyFamilyGroups()
                    Toast.makeText(this@MainActivity, "家庭组创建成功！", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "创建失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showJoinFamilyDialog() {
        val input = EditText(this).apply { hint = "请输入家庭组ID"; setPadding(48, 24, 48, 24) }
        showSmoothInputDialog("加入家庭组", input, "加入") {
            val targetFamilyId = input.text.toString().trim()
            if (targetFamilyId.isEmpty()) { Toast.makeText(this, "请输入家庭组ID", Toast.LENGTH_SHORT).show(); return@showSmoothInputDialog }
            lifecycleScope.launch {
                try {
                    SupabaseUtil.joinFamilyGroup(targetFamilyId, SupabaseUtil.currentUserId(), getDisplayName())
                    loadMyFamilyGroups()
                    Toast.makeText(this@MainActivity, "成功加入家庭组！", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "加入失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showFamilyInfo() {
        currentFamilyGroup?.let { group ->
            @Suppress("UNCHECKED_CAST")
            val names = (group["member_names"] as? List<String>) ?: emptyList()
            val role = if (isAdmin) "管理员" else "成员"
            showSmoothDialog("家庭组信息",
                "家庭组名称：${group["name"]}\n创建者：${group["creator_name"]}\n您的角色：$role\n成员：${names.joinToString("、")}")
        }
    }

    private fun showInviteCode() {
        currentFamilyGroup?.let { group ->
            val familyIdValue = group["id"]?.toString() ?: ""
            val dialog = AlertDialog.Builder(this).setTitle("邀请家人")
                .setMessage("将以下家庭组ID分享给家人：\n\n$familyIdValue\n\n家人在App中输入此ID即可加入")
                .setPositiveButton("复制ID") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("家庭组ID", familyIdValue))
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("关闭", null).create()
            dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
            dialog.show()
        }
    }

    private var memberManageDialog: AlertDialog? = null

    private fun showMemberManage() {
        val group = currentFamilyGroup ?: return
        @Suppress("UNCHECKED_CAST")
        val memberIds = (group["member_ids"] as? List<String>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val memberNames = (group["member_names"] as? List<String>) ?: emptyList()
        val uid = SupabaseUtil.currentUserId()
        val creatorId = group["creator_id"]?.toString() ?: ""

        lifecycleScope.launch {
            val statuses = try { SupabaseUtil.getMemberStatuses(memberIds) } catch (_: Exception) { emptyMap() }
            val now = System.currentTimeMillis()

            val items = memberNames.mapIndexed { i, name ->
                val id = memberIds.getOrNull(i) ?: ""
                val badge = if (id == uid) "（我）" else ""
                val role = if (id == creatorId) " [管理员]" else ""
                val lastActive = statuses[id] ?: 0L
                val isOnline = (now - lastActive) < 5 * 60 * 1000 && lastActive > 0
                val statusTag = if (isOnline) " 🟢在线" else " ⚪离线"
                "$name$badge$role$statusTag"
            }

            val listAdapter = object : android.widget.BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(position: Int) = items[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val view = convertView ?: layoutInflater.inflate(android.R.layout.select_dialog_item, parent, false)
                    val tv = view.findViewById<TextView>(android.R.id.text1)
                    tv.text = items[position]
                    tv.textSize = 14f
                    tv.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_myplaces, 0, 0, 0)
                    tv.compoundDrawablePadding = 24
                    return view
                }
            }

            val addBtn = com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                text = "  通过手机号添加成员"
                textSize = 12f
                setTextColor(resources.getColor(R.color.primary, null))
                setIconResource(android.R.drawable.ic_menu_add)
                iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                iconSize = 32
                cornerRadius = 12
                strokeColor = resources.getColorStateList(R.color.primary)
                strokeWidth = 1
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 8
                }
            }

            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val listView = android.widget.ListView(this@MainActivity).apply {
                    adapter = listAdapter
                    dividerHeight = 0
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                addView(listView)
                if (isAdmin) addView(addBtn)
            }

            memberManageDialog?.dismiss()
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("成员管理（${items.size}人）")
                .setView(container)
                .setNegativeButton("关闭", null)
                .create()
            memberManageDialog = dialog

            val listView = container.getChildAt(0) as android.widget.ListView
            listView.setOnItemClickListener { _, _, which, _ ->
                val memberId = memberIds.getOrNull(which) ?: return@setOnItemClickListener
                val memberName = memberNames.getOrNull(which) ?: return@setOnItemClickListener
                if (memberId == creatorId) {
                    Toast.makeText(this@MainActivity, "不能操作管理员", Toast.LENGTH_SHORT).show()
                    return@setOnItemClickListener
                }
                showMemberActions(memberId, memberName)
            }

            addBtn.setOnClickListener {
                dialog.dismiss()
                showAddMemberByPhone()
            }

            dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
            dialog.show()
        }
    }

    private fun showAddMemberByPhone() {
        val input = EditText(this).apply {
            hint = "请输入成员手机号"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(48, 24, 48, 24)
        }
        showSmoothInputDialog("添加成员", input, "添加") {
            val phone = input.text.toString().trim()
            if (phone.length < 11 || !phone.all { it.isDigit() }) {
                Toast.makeText(this, "请输入正确的手机号", Toast.LENGTH_SHORT).show()
                return@showSmoothInputDialog
            }
            lifecycleScope.launch {
                try {
                    val memberName = SupabaseUtil.addMemberByPhone(familyId, phone)
                    loadMyFamilyGroups()
                    Toast.makeText(this@MainActivity, "已添加成员：$memberName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "添加失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showMemberActions(memberId: String, memberName: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val btnEditName = com.google.android.material.button.MaterialButton(this).apply {
            text = "  修改昵称"
            textSize = 13f
            setTextColor(resources.getColor(R.color.primary, null))
            setIconResource(android.R.drawable.ic_menu_edit)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            iconSize = 36
            cornerRadius = 12
            strokeColor = resources.getColorStateList(R.color.primary)
            strokeWidth = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 10
            }
        }
        val btnRemove = com.google.android.material.button.MaterialButton(this).apply {
            text = "  移除成员"
            textSize = 13f
            setTextColor(resources.getColor(R.color.accent, null))
            setIconResource(android.R.drawable.ic_menu_delete)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            iconSize = 36
            cornerRadius = 12
            strokeColor = resources.getColorStateList(R.color.accent)
            strokeWidth = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(btnEditName)
        container.addView(btnRemove)

        val dialog = AlertDialog.Builder(this)
            .setTitle(memberName)
            .setView(container)
            .setNegativeButton("取消", null)
            .create()

        btnEditName.setOnClickListener {
            dialog.dismiss()
            showEditMemberName(memberId, memberName)
        }
        btnRemove.setOnClickListener {
            dialog.dismiss()
            confirmRemoveMember(memberId, memberName)
        }

        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun showEditMemberName(memberId: String, oldName: String) {
        val input = EditText(this).apply {
            hint = "输入新昵称"
            setText(oldName)
            setPadding(48, 24, 48, 24)
        }
        showSmoothInputDialog("修改昵称", input, "保存") {
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) { Toast.makeText(this, "昵称不能为空", Toast.LENGTH_SHORT).show(); return@showSmoothInputDialog }
            lifecycleScope.launch {
                try {
                    SupabaseUtil.updateMemberName(familyId, memberId, oldName, newName)
                    loadMyFamilyGroups()
                    memberManageDialog?.dismiss()
                    showMemberManage()
                    Toast.makeText(this@MainActivity, "昵称已修改", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "修改失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmRemoveMember(memberId: String, memberName: String) {
        showSmoothDialog("移除成员", "确定要将「$memberName」移出家庭组吗？", "移除") {
            lifecycleScope.launch {
                try {
                    SupabaseUtil.removeMember(familyId, memberId, memberName)
                    loadMyFamilyGroups()
                    memberManageDialog?.dismiss()
                    showMemberManage()
                    Toast.makeText(this@MainActivity, "已移除成员", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "移除失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showEditFamilyInfo() {
        currentFamilyGroup?.let { group ->
            val input = EditText(this).apply {
                hint = "家庭组名称"
                setText(group["name"]?.toString() ?: "")
                setPadding(48, 24, 48, 24)
            }
            showSmoothInputDialog("修改家庭组信息", input, "保存") {
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) { Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show(); return@showSmoothInputDialog }
                lifecycleScope.launch {
                    try {
                        SupabaseUtil.updateFamilyGroup(familyId, newName)
                        loadMyFamilyGroups()
                        Toast.makeText(this@MainActivity, "修改成功", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "修改失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }


    private fun showSmoothDialog(title: String, message: String, positiveBtn: String = "确定", onPositive: (() -> Unit)? = null) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveBtn) { _, _ -> onPositive?.invoke() }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun showSmoothInputDialog(title: String, input: EditText, positiveBtn: String, onPositive: (() -> Unit)) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positiveBtn) { _, _ -> onPositive() }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private suspend fun getDisplayName(): String {
        return try {
            SupabaseUtil.getUserProfile(SupabaseUtil.currentUserId())?.get("display_name")?.toString() ?: "用户"
        } catch (_: Exception) { "用户" }
    }

    private fun navigateToLogin(animate: Boolean) {
        if (animate) {
            binding.root.animate().alpha(0f).setDuration(250).withEndAction {
                startActivity(Intent(this, LoginActivity::class.java))
                overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
                finish()
            }.start()
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
            finish()
        }

    }

    private fun showStorageUsage() {
        binding.btnStorageUsage.isEnabled = false
        lifecycleScope.launch {
            try {
                val usage = SupabaseUtil.getStorageUsage()
                val totalSize = (usage["total_size"] as? Long) ?: 0L
                val availableSize = (usage["available_size"] as? Long) ?: 0L
                val totalCapacity = (usage["total_capacity"] as? Long) ?: 0L
                val usagePercent = (usage["usage_percent"] as? Int) ?: 0
                val photoSize = (usage["photo_size"] as? Long) ?: 0L
                val apkSize = (usage["apk_size"] as? Long) ?: 0L
                val photoFileCount = (usage["photo_file_count"] as? Int) ?: 0
                val photoRecordCount = (usage["photo_record_count"] as? Int) ?: 0
                val familyCount = (usage["family_count"] as? Int) ?: 0
                val memberCount = (usage["member_count"] as? Int) ?: 0
                val apkCount = (usage["apk_count"] as? Int) ?: 0

                fun fmt(bytes: Long) = when {
                    bytes >= 1073741824 -> "%.1f GB".format(bytes / 1073741824.0)
                    bytes >= 1048576 -> "%.1f MB".format(bytes / 1048576.0)
                    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
                    else -> "$bytes B"
                }

                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 0)
                }

                val rows = listOf(
                    "已用 / 总容量" to "${fmt(totalSize)} / ${fmt(totalCapacity)}（${usagePercent}%）",
                    "剩余可用" to fmt(availableSize),
                    "照片文件" to "${fmt(photoSize)}（${photoFileCount}个文件，${photoRecordCount}条记录）",
                    "应用安装包" to "${fmt(apkSize)}（${apkCount}个）",
                    "家庭组" to "$familyCount 个",
                    "成员总数" to "$memberCount 人"
                )

                rows.forEachIndexed { index, (label, value) ->
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 10, 0, 10)
                    }
                    row.addView(android.widget.TextView(this@MainActivity).apply {
                        text = label
                        setTextColor(resources.getColor(R.color.text_secondary, null))
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(android.widget.TextView(this@MainActivity).apply {
                        text = value
                        setTextColor(resources.getColor(R.color.text_primary, null))
                        textSize = 14f
                        gravity = android.view.Gravity.END
                    })
                    container.addView(row)
                    if (index < rows.lastIndex) {
                        container.addView(android.view.View(this@MainActivity).apply {
                            setBackgroundColor(resources.getColor(R.color.divider, null))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 2; bottomMargin = 2 }
                        })
                    }
                }

                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("服务器存储用量")
                    .setView(container)
                    .setPositiveButton("关闭", null)
                    .create()
                dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
                dialog.show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "查询失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnStorageUsage.isEnabled = true
            }
        }
    }
}
