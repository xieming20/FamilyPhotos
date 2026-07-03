const screenNames = {
    login: '登录',
    main: '主页',
    gallery: '照片画廊',
    upload: '上传照片',
    detail: '照片详情'
};

let isLoginMode = true;

function showScreen(screenId) {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    const target = document.getElementById('screen-' + screenId);
    if (target) target.classList.add('active');

    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.screen === screenId);
    });

    document.getElementById('currentScreenName').textContent = screenNames[screenId] || screenId;
    hideDialog();
}

function toggleLoginMode() {
    isLoginMode = !isLoginMode;
    const titleEl = document.querySelector('.login-title');
    const subtitleEl = document.querySelector('.login-subtitle');
    const btnEl = document.getElementById('btnLogin');
    const switchEl = document.getElementById('switchMode');
    const nameField = document.getElementById('displayNameField');

    if (isLoginMode) {
        titleEl.textContent = '欢迎回来';
        subtitleEl.textContent = '登录您的家庭相册账号';
        btnEl.textContent = '登 录';
        switchEl.textContent = '还没有账号？点击注册';
        nameField.style.display = 'none';
    } else {
        titleEl.textContent = '创建账号';
        subtitleEl.textContent = '注册后即可与家人分享照片';
        btnEl.textContent = '注 册';
        switchEl.textContent = '已有账号？点击登录';
        nameField.style.display = 'block';
    }
}

function handleLogin() {
    showScreen('main');
    showToast('登录成功！');
}

function showFamilyDialog() {
    document.getElementById('dialog-family').style.display = 'flex';
}

function showCreateFamily() {
    hideDialog();
    document.getElementById('dialog-create-family').style.display = 'flex';
}

function showJoinFamily() {
    hideDialog();
    document.getElementById('dialog-join-family').style.display = 'flex';
}

function showFamilyInfo() {
    hideDialog();
    document.getElementById('dialog-family-info').style.display = 'flex';
}

function showInviteCode() {
    hideDialog();
    document.getElementById('dialog-invite').style.display = 'flex';
}

function hideDialog() {
    document.querySelectorAll('.dialog-overlay').forEach(d => d.style.display = 'none');
}

function createFamily() {
    const name = document.getElementById('familyNameInput').value.trim();
    if (!name) {
        showToast('请输入家庭组名称');
        return;
    }
    hideDialog();
    document.getElementById('familyInfo').textContent = '家庭组：' + name + '（1人）';
    showToast('家庭组创建成功！');
}

function joinFamily() {
    const code = document.getElementById('inviteCodeInput').value.trim();
    if (code.length !== 6) {
        showToast('邀请码为6位字符');
        return;
    }
    hideDialog();
    showToast('成功加入家庭组！');
}

function copyInviteCode() {
    showToast('邀请码已复制到剪贴板');
}

function simulateSelect() {
    document.getElementById('selectedCount').textContent = '已选择 3 张照片';
    document.getElementById('previewCard').style.display = 'block';
}

function simulateUpload() {
    const progressEl = document.getElementById('uploadProgress');
    const fillEl = document.getElementById('progressFill');
    const textEl = document.getElementById('progressText');

    progressEl.style.display = 'block';
    fillEl.style.width = '0%';

    let progress = 0;
    const interval = setInterval(() => {
        progress += Math.random() * 20 + 5;
        if (progress >= 100) {
            progress = 100;
            clearInterval(interval);
            textEl.textContent = '上传完成！';
            setTimeout(() => {
                progressEl.style.display = 'none';
                fillEl.style.width = '0%';
                showToast('3张照片上传成功！');
            }, 800);
        } else {
            textEl.textContent = '正在上传 ' + Math.round(progress) + '%';
        }
        fillEl.style.width = progress + '%';
    }, 300);
}

function simulateDownload() {
    showToast('照片已保存到相册');
}

function simulateShare() {
    showToast('分享链接已生成');
}

function showToast(message) {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.style.display = 'block';
    setTimeout(() => {
        toast.style.display = 'none';
    }, 2000);
}

document.querySelectorAll('.dialog-overlay').forEach(overlay => {
    overlay.addEventListener('click', (e) => {
        if (e.target === overlay) hideDialog();
    });
});