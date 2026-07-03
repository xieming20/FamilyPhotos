package com.family.photos

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.family.photos.databinding.ActivityLoginBinding
import com.family.photos.util.SupabaseUtil
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (SupabaseUtil.isSignedIn()) {
            navigateToMain()
            return
        }

        playEnterAnimation()
        setupViews()
    }

    private fun playEnterAnimation() {
        val views = listOf(binding.tilEmail, binding.tilPassword, binding.tilDisplayName, binding.btnSubmit, binding.tvSwitchMode, binding.btnShareCodeLogin)
        views.forEachIndexed { i, view ->
            view.alpha = 0f
            view.translationY = 30f
            view.animate().alpha(1f).translationY(0f)
                .setDuration(400).setInterpolator(DecelerateInterpolator())
                .setStartDelay((i + 1) * 80L).start()
        }
    }

    private fun setupViews() {
        updateModeUI()

        binding.tvSwitchMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateModeUI()
        }

        binding.btnSubmit.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val displayName = binding.etDisplayName.text.toString().trim()

            if (!validateInput(email, password, displayName)) return@setOnClickListener

            binding.btnSubmit.isEnabled = false
            binding.progressBar.visibility = android.view.View.VISIBLE

            lifecycleScope.launch {
                try {
                    if (isLoginMode) SupabaseUtil.signIn(email, password)
                    else SupabaseUtil.signUp(email, password, displayName)
                    navigateToMain()
                } catch (e: Exception) {
                    showError(if (isLoginMode) "登录失败" else "注册失败", e.message)
                } finally {
                    binding.btnSubmit.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE
                }
            }
        }

        binding.btnShareCodeLogin.setOnClickListener { showShareCodeLoginDialog() }
    }

    private fun showShareCodeLoginDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etCode = EditText(this).apply {
            hint = "请输入6位分享码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(0, 12, 0, 12)
        }
        val etName = EditText(this).apply {
            hint = "请输入您的昵称"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(0, 12, 0, 12)
        }
        container.addView(etCode)
        container.addView(etName)

        val dialog = AlertDialog.Builder(this)
            .setTitle("使用邀请码登录")
            .setView(container)
            .setPositiveButton("登录") { _, _ ->
                val code = etCode.text.toString().trim()
                val name = etName.text.toString().trim()
                if (code.length != 6 || !code.all { it.isLetterOrDigit() }) {
                    Toast.makeText(this, "邀请码为6位字母或数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入昵称", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                doShareCodeLogin(code, name)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation
        dialog.show()
    }

    private fun doShareCodeLogin(code: String, displayName: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnSubmit.isEnabled = false
        binding.btnShareCodeLogin.isEnabled = false

        lifecycleScope.launch {
            try {
                val familyGroup = SupabaseUtil.inviteCodeLogin(code, displayName)
                val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                    putExtra("family_id", familyGroup["id"]?.toString() ?: "")
                    putExtra("family_name", familyGroup["name"]?.toString() ?: "")
                    putExtra("invite_code", code)
                }
                startActivity(intent)
                overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
                finish()
            } catch (e: Exception) {
                showError("分享码登录失败", e.message)
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnSubmit.isEnabled = true
                binding.btnShareCodeLogin.isEnabled = true
            }
        }
    }

    private fun updateModeUI() {
        if (isLoginMode) {
            binding.tvTitle.text = "欢迎回来"
            binding.tvSubtitle.text = "登录您的时光相册账号"
            binding.btnSubmit.text = "登 录"
            binding.tvSwitchMode.text = "还没有账号？点击注册"
            binding.tilDisplayName.visibility = android.view.View.GONE
        } else {
            binding.tvTitle.text = "创建账号"
            binding.tvSubtitle.text = "注册后即可与家人分享照片"
            binding.btnSubmit.text = "注 册"
            binding.tvSwitchMode.text = "已有账号？点击登录"
            binding.tilDisplayName.visibility = android.view.View.VISIBLE
        }
    }

    private fun validateInput(email: String, password: String, displayName: String): Boolean {
        if (TextUtils.isEmpty(email)) { binding.etEmail.error = "请输入邮箱地址"; return false }
        if (TextUtils.isEmpty(password) || password.length < 6) { binding.etPassword.error = "密码至少需要6位"; return false }
        if (!isLoginMode && TextUtils.isEmpty(displayName)) { binding.etDisplayName.error = "请输入您的昵称"; return false }
        return true
    }

    private fun showError(title: String, detail: String?) {
        Toast.makeText(this, "$title：${detail ?: "未知错误"}", Toast.LENGTH_LONG).show()
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        finish()
    }
}
