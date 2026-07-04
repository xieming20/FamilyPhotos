package com.family.photos

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
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

        setupViews()
        playEnterAnimation()
    }

    private fun playEnterAnimation() {
        val views = listOf(binding.cardAdminLogin, binding.cardMemberLogin)
        views.forEachIndexed { i, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate().alpha(1f).translationY(0f)
                .setDuration(500).setInterpolator(DecelerateInterpolator())
                .setStartDelay((i + 1) * 120L).start()
        }
    }

    private fun setupViews() {
        showRoleSelect()

        binding.cardAdminLogin.setOnClickListener { showAdminLogin() }
        binding.cardMemberLogin.setOnClickListener { showMemberLogin() }

        binding.tvBackToRole.setOnClickListener { showRoleSelect() }
        binding.tvBackToRole2.setOnClickListener { showRoleSelect() }

        binding.tvSwitchMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateModeUI()
        }

        binding.btnSubmit.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val displayName = binding.etDisplayName.text.toString().trim()
            if (!validateInput(email, password, displayName)) return@setOnClickListener
            doAdminLogin(email, password, displayName)
        }

        binding.btnMemberLogin.setOnClickListener {
            val phone = binding.etPhone.text.toString().trim()
            val name = binding.etMemberName.text.toString().trim()
            if (phone.length < 11 || !phone.all { it.isDigit() }) {
                Toast.makeText(this, "请输入11位手机号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doMemberLogin(phone, name)
        }
    }

    private fun showRoleSelect() {
        binding.roleSelectArea.visibility = View.VISIBLE
        binding.adminLoginArea.visibility = View.GONE
        binding.memberLoginArea.visibility = View.GONE
    }

    private fun showAdminLogin() {
        binding.roleSelectArea.visibility = View.GONE
        binding.adminLoginArea.visibility = View.VISIBLE
        binding.memberLoginArea.visibility = View.GONE
        updateModeUI()
        animateArea(binding.adminLoginArea)
    }

    private fun showMemberLogin() {
        binding.roleSelectArea.visibility = View.GONE
        binding.adminLoginArea.visibility = View.GONE
        binding.memberLoginArea.visibility = View.VISIBLE
        animateArea(binding.memberLoginArea)
    }

    private fun animateArea(area: View) {
        area.alpha = 0f
        area.translationY = 30f
        area.animate().alpha(1f).translationY(0f)
            .setDuration(400).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun doAdminLogin(email: String, password: String, displayName: String) {
        binding.btnSubmit.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                if (isLoginMode) SupabaseUtil.signIn(email, password)
                else SupabaseUtil.signUp(email, password, displayName)
                navigateToMain()
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "${if (isLoginMode) "登录失败" else "注册失败"}：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnSubmit.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun doMemberLogin(phone: String, displayName: String) {
        binding.btnMemberLogin.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = SupabaseUtil.phoneLogin(phone, displayName)
                val intent = Intent(this@LoginActivity, MainActivity::class.java)
                if (result.isNotEmpty()) {
                    intent.putExtra("family_id", result["id"]?.toString() ?: "")
                    intent.putExtra("family_name", result["name"]?.toString() ?: "")
                }
                startActivity(intent)
                overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "登录失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnMemberLogin.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateModeUI() {
        if (isLoginMode) {
            binding.tvTitle.text = "管理员登录"
            binding.tvSubtitle.text = "登录您的时光相册账号"
            binding.btnSubmit.text = "登 录"
            binding.tvSwitchMode.text = "还没有账号？点击注册"
            binding.tilDisplayName.visibility = View.GONE
        } else {
            binding.tvTitle.text = "注册账号"
            binding.tvSubtitle.text = "注册后即可创建和管理家庭相册"
            binding.btnSubmit.text = "注 册"
            binding.tvSwitchMode.text = "已有账号？点击登录"
            binding.tilDisplayName.visibility = View.VISIBLE
        }
    }

    private fun validateInput(email: String, password: String, displayName: String): Boolean {
        if (TextUtils.isEmpty(email)) { binding.etEmail.error = "请输入邮箱地址"; return false }
        if (TextUtils.isEmpty(password) || password.length < 6) { binding.etPassword.error = "密码至少需要6位"; return false }
        if (!isLoginMode && TextUtils.isEmpty(displayName)) { binding.etDisplayName.error = "请输入您的昵称"; return false }
        return true
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        finish()
    }
}
