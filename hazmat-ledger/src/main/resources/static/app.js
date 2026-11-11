/*
 * 演示前端：Vue 3 全局构建 + hash 路由 + sessionStorage 存 token。
 *
 * 不引构建链和组件库，目的不是做成生产前端，而是让「登录 → 建档 → 入库 →
 * 领用 → 审批 → 出库 → 核销 → 盘点 → 报废」这条链路能在浏览器里完整走一遍。
 * 同一份 jar 同时提供页面和 API，没有 CORS，也不需要 Node 构建阶段。
 */
(function () {
    'use strict';

    const { createApp } = Vue;

    const TOKEN_KEY = 'hazmat.token';
    const USER_KEY = 'hazmat.user';

    /* ---------------- 会话与请求 ---------------- */

    const store = Vue.reactive({
        token: sessionStorage.getItem(TOKEN_KEY) || '',
        user: readUser(),
        toast: ''
    });

    let toastTimer = 0;

    function readUser() {
        try {
            return JSON.parse(sessionStorage.getItem(USER_KEY) || 'null');
        } catch (e) {
            return null;
        }
    }

    function setSession(login) {
        store.token = login.token;
        store.user = login.user;
        sessionStorage.setItem(TOKEN_KEY, login.token);
        sessionStorage.setItem(USER_KEY, JSON.stringify(login.user));
    }

    function clearSession() {
        store.token = '';
        store.user = null;
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(USER_KEY);
    }

    function notify(message) {
        store.toast = message;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { store.toast = ''; }, 2600);
    }

    function qs(params) {
        const search = new URLSearchParams();
        Object.entries(params || {}).forEach(([key, value]) => {
            if (value !== undefined && value !== null && value !== '') {
                search.set(key, value);
            }
        });
        const text = search.toString();
        return text ? '?' + text : '';
    }

    async function api(method, path, body) {
        const headers = { 'Accept': 'application/json' };
        if (body !== undefined) {
            headers['Content-Type'] = 'application/json';
        }
        if (store.token) {
            headers['Authorization'] = 'Bearer ' + store.token;
        }
        const response = await fetch(path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body)
        });

        const text = await response.text();
        let payload = null;
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch (e) {
                payload = null;
            }
        }

        if (response.status === 401) {
            // 只有「本来带着 token」才是一次登录过期，需要跳回登录页。
            // 登录接口密码错误同样返回 401，那时跳转会清空刚输入的表单和错误提示。
            const hadToken = !!store.token;
            clearSession();
            if (hadToken) {
                go('/login');
            }
            throw new Error((payload && payload.message) || '登录已过期，请重新登录');
        }
        if (!response.ok) {
            throw new Error((payload && payload.message) || ('请求失败：HTTP ' + response.status));
        }
        return payload ? payload.data : null;
    }

    /**
     * 没有返回体的接口（注册、全局下线）用这个包一层。
     * 直接判断 api() 的返回值会把「成功但 data 为 null」误当成失败。
     */
    async function apiOk(method, path, body) {
        await api(method, path, body);
        return true;
    }

    /* ---------------- 路由 ---------------- */

    const router = Vue.reactive({
        path: location.hash.replace(/^#/, '') || '/chemicals'
    });

    window.addEventListener('hashchange', () => {
        router.path = location.hash.replace(/^#/, '') || '/chemicals';
    });

    function go(path) {
        // 比较的是地址栏而不是 router.path：首次进入时没有 hash，
        // router.path 已经回落成 /chemicals，只看它会导致地址栏一直停在 /。
        if (location.hash === '#' + path) {
            return;
        }
        location.hash = '#' + path;
    }

    /* ---------------- 公共方法 ---------------- */

    const STATUS_LABELS = {
        PENDING: '待审批',
        PENDING_SECOND: '待二级复核',
        APPROVED: '已通过',
        REJECTED: '已驳回',
        ISSUED: '已出库',
        CLOSED: '已核销',
        CANCELLED: '已撤销',
        DRAFT: '草稿',
        SUBMITTED: '已提交',
        REVIEWED: '已复核',
        CONFIRMED: '已确认',
        RUNNING: '运行中',
        FAILED: '失败',
        DONE: '完成',
        DEAD: '重试耗尽'
    };

    const STATUS_CLASSES = {
        PENDING: 'warn',
        PENDING_SECOND: 'warn',
        APPROVED: 'ok',
        ISSUED: 'ok',
        CLOSED: 'muted',
        REJECTED: 'bad',
        CANCELLED: 'muted',
        DRAFT: 'muted',
        SUBMITTED: 'warn',
        REVIEWED: 'ok',
        CONFIRMED: 'ok',
        RUNNING: 'warn',
        FAILED: 'bad',
        DONE: 'ok',
        DEAD: 'bad'
    };

    function expireInYears(years) {
        const date = new Date();
        date.setFullYear(date.getFullYear() + years);
        return date.toISOString().slice(0, 10);
    }

    const pageMixin = {
        data() {
            return { loading: false, error: '', busy: '' };
        },
        computed: {
            path() { return router.path; },
            segments() { return router.path.split('/').filter(Boolean); },
            routeParam() { return this.segments[1] || ''; },
            isAdmin() { return !!store.user && store.user.role === 'ADMIN'; },
            isApprover() { return !!store.user && (store.user.role === 'APPROVER' || store.user.role === 'ADMIN'); },
            me() { return store.user ? store.user.username : ''; }
        },
        methods: {
            go,
            notify,
            num(value) {
                return value === null || value === undefined || value === '' ? '—' : value;
            },
            dt(value) {
                if (!value) {
                    return '—';
                }
                return String(value).replace('T', ' ').slice(0, 19);
            },
            score(value) {
                const n = Number(value);
                return Number.isFinite(n) ? n.toFixed(3) : this.num(value);
            },
            statusLabel(status) {
                return STATUS_LABELS[status] || status || '—';
            },
            statusClass(status) {
                return STATUS_CLASSES[status] || '';
            },
            roleLabel(role) {
                return { USER: '普通用户', APPROVER: '审批员', ADMIN: '管理员' }[role] || role;
            },
            async run(action) {
                this.loading = true;
                this.error = '';
                try {
                    return await action();
                } catch (e) {
                    this.error = e.message;
                    return null;
                } finally {
                    this.loading = false;
                }
            },
            async act(key, action) {
                this.busy = key;
                this.error = '';
                try {
                    return await action();
                } catch (e) {
                    this.error = e.message;
                    return null;
                } finally {
                    this.busy = '';
                }
            }
        }
    };

    /* ---------------- 登录 / 注册 ---------------- */

    const app = createApp({
        mixins: [pageMixin],
        data() {
            return { store };
        },
        computed: {
            page() {
                const head = this.segments[0] || 'chemicals';
                if (!store.user) {
                    return head === 'register' ? 'register-page' : 'login-page';
                }
                const map = {
                    chemicals: this.segments.length > 1 ? 'chemical-detail-page' : 'chemicals-page',
                    expiring: 'expiring-page',
                    requisitions: this.segments.length > 1 ? 'requisition-detail-page' : 'requisitions-page',
                    stocktakes: this.segments.length > 1 ? 'stocktake-detail-page' : 'stocktakes-page',
                    scraps: 'scraps-page',
                    msds: 'msds-page',
                    ops: 'ops-page',
                    users: 'users-page'
                };
                return map[head] || 'not-found-page';
            },
            navItems() {
                return [
                    { path: '/chemicals', label: '试剂档案' },
                    { path: '/expiring', label: '效期预警' },
                    { path: '/requisitions', label: '领用申请' },
                    { path: '/stocktakes', label: '盘点' },
                    { path: '/scraps', label: '报废' },
                    { path: '/msds', label: 'MSDS 检索' },
                    { path: '/ops', label: '运维告警', admin: true },
                    { path: '/users', label: '用户管理', admin: true }
                ].filter(item => !item.admin || (store.user && store.user.role === 'ADMIN'));
            },
            currentLabel() {
                const hit = this.navItems.find(item =>
                    this.path === item.path || this.path.indexOf(item.path + '/') === 0);
                return hit ? hit.label : '页面';
            }
        },
        methods: {
            logout() {
                clearSession();
                go('/login');
            },
            async logoutAll() {
                const done = await this.act('logout-all', () => apiOk('POST', '/api/auth/logout-all'));
                if (done) {
                    notify('已让所有设备上的登录失效');
                    clearSession();
                    go('/login');
                }
            }
        },
        template: `
<div v-if="!store.user" class="auth-shell">
    <component :is="page" :key="path"></component>
</div>
<div v-else class="app-shell">
    <aside class="sidebar">
        <div class="brand">
            <span class="brand-mark">危</span>
            <span class="brand-text">
                <strong>危化品台账</strong>
                <small>LAB INVENTORY</small>
            </span>
        </div>
        <div class="nav-label">工作区</div>
        <a v-for="item in navItems" :key="item.path" class="nav-link"
           :class="{ active: path === item.path || path.indexOf(item.path + '/') === 0 }"
           :href="'#' + item.path">{{ item.label }}</a>
        <div class="foot">
            <span class="avatar">{{ (store.user.displayName || store.user.username).slice(0, 1) }}</span>
            <div>
                <div class="foot-name">{{ store.user.displayName }}</div>
                <div>{{ roleLabel(store.user.role) }}</div>
            </div>
        </div>
    </aside>
    <div class="main">
        <header class="topbar">
            <span class="crumb">
                <span>工作台</span>
                <span class="sep">/</span>
                <strong>{{ currentLabel }}</strong>
            </span>
            <span class="who">
                <strong>{{ store.user.displayName }}</strong>（{{ roleLabel(store.user.role) }}）
                <button class="btn ghost sm" style="margin-left:8px" @click="logout">退出</button>
                <button class="btn ghost sm" style="margin-left:6px" @click="logoutAll">全部设备下线</button>
            </span>
        </header>
        <main class="content">
            <component :is="page" :key="path"></component>
        </main>
    </div>
</div>
<div v-if="store.toast" class="toast">{{ store.toast }}</div>`
    });

    app.component('pager', {
        props: ['page'],
        emits: ['change'],
        template: `
<div class="pager" v-if="page">
    <span>共 {{ page.totalElements }} 条 · 第 {{ page.page + 1 }} / {{ Math.max(page.totalPages, 1) }} 页</span>
    <button class="btn ghost sm" :disabled="page.page <= 0" @click="$emit('change', page.page - 1)">上一页</button>
    <button class="btn ghost sm" :disabled="!page.hasNext" @click="$emit('change', page.page + 1)">下一页</button>
</div>`
    });

    app.component('not-found-page', {
        mixins: [pageMixin],
        template: `
<div class="card">
    <h1>页面不存在</h1>
    <p class="hint" style="margin:8px 0 14px">地址可能写错了。回到试剂档案继续。</p>
    <a class="btn" href="#/chemicals">返回试剂档案</a>
</div>`
    });

    app.component('login-page', {
        mixins: [pageMixin],
        data() {
            return { username: '', password: '' };
        },
        methods: {
            async submit() {
                const login = await this.run(() => api('POST', '/api/auth/login', {
                    username: this.username,
                    password: this.password
                }));
                if (login) {
                    setSession(login);
                    notify('已登录：' + login.user.displayName);
                    go('/chemicals');
                }
            }
        },
        template: `
<div class="auth-layout">
    <aside class="auth-brand">
        <div class="logo-row"><span class="brand-mark">危</span><span>危化品台账</span></div>
        <h2>实验室试剂的<br>全流程台账</h2>
        <p>从建档、入库，到领用审批、盘点、报废，每一步都有单据和留痕。</p>
        <ul>
            <li>高危试剂双人复核</li>
            <li>批次级库存与台账对账</li>
            <li>MSDS 检索与安全摘要</li>
        </ul>
        <div class="auth-brand-foot">演示环境 · 数据仅用于流程验证</div>
    </aside>
    <div class="auth-card">
        <div class="brand">登录</div>
        <p class="sub">使用实验室账号进入台账系统</p>
        <div v-if="error" class="alert error">{{ error }}</div>
        <form @submit.prevent="submit">
            <label class="field"><span>用户名</span>
                <input v-model.trim="username" autocomplete="username" required>
            </label>
            <label class="field"><span>密码</span>
                <input v-model="password" type="password" autocomplete="current-password" required>
            </label>
            <button class="btn" type="submit" :disabled="loading" style="width:100%">
                {{ loading ? '登录中…' : '登录' }}
            </button>
        </form>
        <p class="hint" style="margin-top:14px">
            没有账号？<a href="#/register">提交注册申请</a>，管理员启用后才能登录。
        </p>
    </div>
</div>`
    });

    app.component('register-page', {
        mixins: [pageMixin],
        data() {
            return { form: { username: '', displayName: '', password: '' }, done: false };
        },
        methods: {
            async submit() {
                const done = await this.run(() => apiOk('POST', '/api/auth/register', this.form));
                if (done) {
                    this.done = true;
                    this.form = { username: '', displayName: '', password: '' };
                }
            }
        },
        template: `
<div class="auth-card">
    <div class="brand">提交注册申请</div>
    <p class="sub">账号默认停用，需要管理员在「用户管理」里启用。</p>
    <div v-if="error" class="alert error">{{ error }}</div>
    <div v-if="done" class="alert ok">
        注册申请已提交。如果用户名已被占用，系统不会另行提示，请联系管理员确认。
    </div>
    <form v-if="!done" @submit.prevent="submit">
        <label class="field"><span>用户名（3~100 个字符）</span>
            <input v-model.trim="form.username" minlength="3" maxlength="100" required>
        </label>
        <label class="field"><span>显示名</span>
            <input v-model.trim="form.displayName" maxlength="100" required>
        </label>
        <label class="field"><span>密码（8~64 个字符）</span>
            <input v-model="form.password" type="password" minlength="8" maxlength="64" required>
        </label>
        <button class="btn" type="submit" :disabled="loading" style="width:100%">
            {{ loading ? '提交中…' : '提交申请' }}
        </button>
    </form>
    <p class="hint" style="margin-top:14px"><a href="#/login">返回登录</a></p>
</div>`
    });

    /* ---------------- 试剂档案 ---------------- */

    app.component('chemicals-page', {
        mixins: [pageMixin],
        data() {
            return {
                filters: { keyword: '', category: '', hazardLevel: '' },
                page: 0,
                size: 20,
                result: null,
                showCreate: false,
                form: {
                    casNo: '', name: '', category: '', hazardLevel: '中', unit: 'mL',
                    spec: '', msdsUrl: '', postOpenDays: ''
                }
            };
        },
        created() {
            this.load(0);
        },
        methods: {
            async load(page) {
                const target = typeof page === 'number' ? page : this.page;
                await this.run(async () => {
                    this.result = await api('GET', '/api/chemicals' + qs({
                        keyword: this.filters.keyword,
                        category: this.filters.category,
                        hazardLevel: this.filters.hazardLevel,
                        page: target,
                        size: this.size
                    }));
                    this.page = target;
                });
            },
            async create() {
                const f = this.form;
                const body = {
                    casNo: f.casNo,
                    name: f.name,
                    category: f.category,
                    hazardLevel: f.hazardLevel,
                    unit: f.unit,
                    spec: f.spec || null,
                    msdsUrl: f.msdsUrl || null,
                    postOpenDays: f.postOpenDays === '' ? null : Number(f.postOpenDays)
                };
                const saved = await this.act('create', () => api('POST', '/api/chemicals', body));
                if (saved) {
                    notify('已创建试剂：' + saved.name);
                    this.showCreate = false;
                    this.form = { casNo: '', name: '', category: '', hazardLevel: '中', unit: 'mL', spec: '', msdsUrl: '', postOpenDays: '' };
                    this.load(0);
                }
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>试剂档案</h1>
            <p class="hint">列表读接口对所有登录用户开放；建档、入库、开封、应急报废限管理员。</p>
        </div>
        <button v-if="isAdmin" class="btn" @click="showCreate = !showCreate">
            {{ showCreate ? '收起表单' : '新建试剂' }}
        </button>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <form v-if="showCreate" class="card form-grid" @submit.prevent="create">
        <label class="field"><span>CAS 号 *</span><input v-model.trim="form.casNo" required></label>
        <label class="field"><span>名称 *</span><input v-model.trim="form.name" required></label>
        <label class="field"><span>危险类别 *</span><input v-model.trim="form.category" placeholder="易燃 / 腐蚀" required></label>
        <label class="field"><span>危险等级 *</span>
            <select v-model="form.hazardLevel">
                <option>低</option><option>中</option><option>高</option>
            </select>
        </label>
        <label class="field"><span>计量单位 *</span><input v-model.trim="form.unit" required></label>
        <label class="field"><span>规格</span><input v-model.trim="form.spec"></label>
        <label class="field"><span>MSDS 地址</span><input v-model.trim="form.msdsUrl"></label>
        <label class="field"><span>开封后可用天数</span>
            <input v-model="form.postOpenDays" type="number" min="1" max="3650">
        </label>
        <div class="form-actions" style="grid-column:1/-1">
            <button class="btn" type="submit" :disabled="busy === 'create'">
                {{ busy === 'create' ? '保存中…' : '保存' }}
            </button>
        </div>
    </form>

    <form class="card filters" @submit.prevent="load(0)">
        <label class="field"><span>关键词</span>
            <input v-model.trim="filters.keyword" placeholder="名称或 CAS">
        </label>
        <label class="field"><span>危险类别</span>
            <input v-model.trim="filters.category" placeholder="精确匹配">
        </label>
        <label class="field"><span>危险等级</span>
            <select v-model="filters.hazardLevel">
                <option value="">全部</option><option>低</option><option>中</option><option>高</option>
            </select>
        </label>
        <div class="form-actions">
            <button class="btn" type="submit" :disabled="loading">查询</button>
            <button class="btn ghost" type="button" @click="filters = { keyword: '', category: '', hazardLevel: '' }; load(0)">重置</button>
        </div>
    </form>

    <div class="card">
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>ID</th><th>名称</th><th>CAS</th><th>类别</th>
                    <th>等级</th><th>单位</th><th>规格</th><th></th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="c in (result ? result.content : [])" :key="c.id">
                    <td class="mono">{{ c.id }}</td>
                    <td><a :href="'#/chemicals/' + c.id">{{ c.name }}</a></td>
                    <td class="mono">{{ c.casNo }}</td>
                    <td>{{ c.category }}</td>
                    <td><span class="badge" :class="c.hazardLevel === '高' ? 'bad' : (c.hazardLevel === '中' ? 'warn' : 'ok')">{{ c.hazardLevel }}</span></td>
                    <td>{{ c.unit }}</td>
                    <td>{{ num(c.spec) }}</td>
                    <td class="actions"><a :href="'#/chemicals/' + c.id">库存与台账</a></td>
                </tr>
                <tr v-if="result && result.content.length === 0">
                    <td colspan="8" class="empty">没有匹配的试剂</td>
                </tr>
                </tbody>
            </table>
        </div>
        <pager :page="result" @change="load"></pager>
    </div>
</section>`
    });

    app.component('chemical-detail-page', {
        mixins: [pageMixin],
        data() {
            return {
                chemical: null,
                stock: null,
                incompat: null,
                ledger: null,
                ledgerPage: 0,
                reconcileResult: null,
                inbound: { batchNo: '', quantity: '', location: '', expireDate: expireInYears(1) },
                openBusy: '',
                scrapBatch: null,
                scrap: { quantity: '', reason: '' },
                safetyQ: '',
                safety: null,
                safetyLoading: false
            };
        },
        created() {
            this.load();
        },
        methods: {
            async load() {
                const id = this.routeParam;
                if (!id) {
                    return;
                }
                const data = await this.run(async () => {
                    const [chemical, stock, incompat] = await Promise.all([
                        api('GET', '/api/chemicals/' + id),
                        api('GET', '/api/chemicals/' + id + '/stock'),
                        api('GET', '/api/chemicals/' + id + '/incompatibilities')
                    ]);
                    return { chemical, stock, incompat };
                });
                if (data) {
                    this.chemical = data.chemical;
                    this.stock = data.stock;
                    this.incompat = data.incompat;
                    if (!this.safetyQ) {
                        this.safetyQ = data.chemical.name + ' 急救';
                    }
                    this.loadLedger(0);
                }
            },
            async loadLedger(page) {
                const target = typeof page === 'number' ? page : this.ledgerPage;
                await this.run(async () => {
                    this.ledger = await api('GET', '/api/chemicals/' + this.routeParam + '/ledger' + qs({ page: target, size: 10 }));
                    this.ledgerPage = target;
                });
            },
            async inboundSubmit() {
                const body = {
                    batchNo: this.inbound.batchNo,
                    quantity: Number(this.inbound.quantity),
                    location: this.inbound.location,
                    expireDate: this.inbound.expireDate
                };
                const saved = await this.act('inbound', () => api('POST', '/api/chemicals/' + this.routeParam + '/batches', body));
                if (saved) {
                    notify('已入库批次 ' + saved.batchNo);
                    this.inbound = { batchNo: '', quantity: '', location: '', expireDate: expireInYears(1) };
                    this.load();
                }
            },
            async openBatch(batch) {
                const done = await this.act('open-' + batch.id, () => api('POST', '/api/chemicals/' + this.routeParam + '/batches/' + batch.id + '/open'));
                if (done) {
                    notify('已记录开封：' + done.batchNo);
                    this.load();
                }
            },
            startScrap(batch) {
                this.scrapBatch = batch;
                this.scrap = { quantity: '', reason: '' };
            },
            async scrapSubmit() {
                const batch = this.scrapBatch;
                const body = { quantity: Number(this.scrap.quantity), reason: this.scrap.reason };
                const done = await this.act('scrap-' + batch.id, () => api(
                    'POST', '/api/chemicals/' + this.routeParam + '/batches/' + batch.id + '/scrap', body));
                if (done) {
                    notify('已应急报废 ' + done.scrappedQuantity + ' ' + this.chemical.unit);
                    this.scrapBatch = null;
                    this.load();
                }
            },
            async runSafety() {
                if (!this.safetyQ) {
                    return;
                }
                this.safetyLoading = true;
                this.error = '';
                try {
                    this.safety = await api('GET', '/api/chemicals/' + this.routeParam + '/safety'
                        + qs({ q: this.safetyQ, topK: 5 }));
                } catch (e) {
                    this.error = e.message;
                } finally {
                    this.safetyLoading = false;
                }
            },
            async reconcile() {
                const result = await this.act('reconcile', () => api('GET', '/api/chemicals/' + this.routeParam + '/reconcile'));
                if (result) {
                    this.reconcileResult = result;
                }
            }
        },
        template: `
<div v-if="error && !chemical" class="alert error">{{ error }}</div>
<section v-if="chemical">
    <div class="page-head">
        <div>
            <h1>{{ chemical.name }}</h1>
            <p class="hint mono">{{ chemical.casNo }} · {{ chemical.spec || '无规格' }}</p>
        </div>
        <a class="btn ghost" href="#/chemicals">返回列表</a>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <div class="card">
        <div class="kv">
            <div><div class="k">危险类别</div><div class="v">{{ chemical.category }}</div></div>
            <div><div class="k">危险等级</div><div class="v">{{ chemical.hazardLevel }}</div></div>
            <div><div class="k">计量单位</div><div class="v">{{ chemical.unit }}</div></div>
            <div><div class="k">开封后可用天数</div><div class="v">{{ num(chemical.postOpenDays) }}</div></div>
            <div><div class="k">MSDS</div><div class="v">
                <a v-if="chemical.msdsUrl" :href="chemical.msdsUrl" target="_blank" rel="noopener">外部链接</a>
                <span v-else>—</span>
            </div></div>
        </div>
    </div>

    <form v-if="isAdmin" class="card form-grid" @submit.prevent="inboundSubmit">
        <h2 style="grid-column:1/-1">入库</h2>
        <label class="field"><span>批次号 *</span><input v-model.trim="inbound.batchNo" required></label>
        <label class="field"><span>数量 *</span><input v-model="inbound.quantity" type="number" step="0.001" min="0.001" required></label>
        <label class="field"><span>存放位置 *</span><input v-model.trim="inbound.location" placeholder="柜 A-1" required></label>
        <label class="field"><span>有效期 *</span><input v-model="inbound.expireDate" type="date" required></label>
        <div class="form-actions" style="grid-column:1/-1">
            <button class="btn" type="submit" :disabled="busy === 'inbound'">
                {{ busy === 'inbound' ? '提交中…' : '确认入库' }}
            </button>
        </div>
    </form>

    <div class="card">
        <div class="card-head">
            <h2>库存</h2>
            <span class="hint">可用总量：<strong>{{ stock ? stock.totalAvailable : '—' }}</strong> {{ chemical.unit }}</span>
        </div>
        <div v-if="stock && stock.alerts && stock.alerts.length" class="alert warn">
            <div v-for="(a, i) in stock.alerts" :key="i">{{ a }}</div>
        </div>
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>批次</th><th>位置</th><th class="num">初始量</th><th class="num">可用量</th>
                    <th>有效期</th><th>可用截止日</th><th>剩余天数</th><th>开封</th><th>操作</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="b in (stock ? stock.batches : [])" :key="b.id">
                    <td class="mono">{{ b.batchNo }}</td>
                    <td>{{ b.location }}</td>
                    <td class="num">{{ b.initialQuantity }}</td>
                    <td class="num">{{ b.availableQuantity }}</td>
                    <td>{{ b.expireDate }}</td>
                    <td>{{ b.usableUntil }}</td>
                    <td>
                        <span class="badge" :class="b.daysToUsable < 0 ? 'bad' : (b.daysToUsable <= 7 ? 'warn' : 'ok')">
                            {{ b.daysToUsable }} 天
                        </span>
                    </td>
                    <td>
                        <span v-if="b.openedAt">{{ dt(b.openedAt) }}</span>
                        <span v-else class="hint">未开封</span>
                    </td>
                    <td class="actions">
                        <template v-if="isAdmin">
                            <button v-if="!b.openedAt" class="btn ghost sm" :disabled="openBusy === b.id"
                                    @click="openBatch(b)">开封</button>
                            <button class="btn bad sm" @click="startScrap(b)">应急报废</button>
                        </template>
                        <span v-else class="hint">—</span>
                    </td>
                </tr>
                <tr v-if="!stock || stock.batches.length === 0">
                    <td colspan="9" class="empty">暂无批次，先入库</td>
                </tr>
                </tbody>
            </table>
        </div>

        <form v-if="scrapBatch" class="card" style="margin-top:12px;background:#fbfcfd" @submit.prevent="scrapSubmit">
            <h3>应急报废：批次 {{ scrapBatch.batchNo }}（当前可用 {{ scrapBatch.availableQuantity }}）</h3>
            <p class="hint" style="margin:4px 0 10px">
                常规报废请走「报废」页的申请审批流程；这里只用于瓶子打碎、泄漏处置等当场销账的情况。
            </p>
            <div class="form-grid">
                <label class="field"><span>报废量 *</span><input v-model="scrap.quantity" type="number" step="0.001" min="0.001" required></label>
                <label class="field"><span>原因 *</span><input v-model.trim="scrap.reason" required></label>
            </div>
            <div class="form-actions">
                <button class="btn bad" type="submit" :disabled="busy === 'scrap-' + scrapBatch.id">确认报废</button>
                <button class="btn ghost" type="button" @click="scrapBatch = null">取消</button>
            </div>
        </form>
    </div>

    <div class="card">
        <div class="card-head">
            <h2>台账流水</h2>
            <button class="btn ghost sm" @click="reconcile">对账检查</button>
        </div>
        <div v-if="reconcileResult" class="alert" :class="reconcileResult.consistent ? 'ok' : 'bad'">
            账面可用 {{ reconcileResult.totalAvailable }}，流水合计 {{ reconcileResult.totalLedger }}，
            {{ reconcileResult.consistent ? '一致' : '不一致，需人工核查' }}。
        </div>
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>时间</th><th>类型</th><th class="num">变动量</th><th class="num">变动后余额</th>
                    <th>关联</th><th>操作人</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="l in (ledger ? ledger.content : [])" :key="l.id">
                    <td>{{ dt(l.createdAt) }}</td>
                    <td><span class="badge">{{ l.changeType }}</span></td>
                    <td class="num">{{ l.quantity }}</td>
                    <td class="num">{{ l.balanceAfter }}</td>
                    <td class="mono">{{ l.refType }}#{{ num(l.refId) }}</td>
                    <td>{{ l.operator }}</td>
                </tr>
                <tr v-if="!ledger || ledger.content.length === 0">
                    <td colspan="6" class="empty">暂无流水</td>
                </tr>
                </tbody>
            </table>
        </div>
        <pager :page="ledger" @change="loadLedger"></pager>
        <p class="hint" style="margin-top:6px">
            注意：SCRAP 的关联 id 是批次 id，SCRAP_ORDER 的关联 id 是报废单 id，需先看类型再解释 id。
        </p>
    </div>

    <div class="grid-2">
        <div class="card">
            <h2>MSDS 安全检索</h2>
            <form class="filters" style="margin:10px 0" @submit.prevent="runSafety">
                <label class="field"><span>问题或关键词</span><input v-model.trim="safetyQ" required></label>
                <div class="form-actions">
                    <button class="btn" type="submit" :disabled="safetyLoading">
                        {{ safetyLoading ? '检索中…' : '检索' }}
                    </button>
                </div>
            </form>
            <div v-if="safety">
                <div class="alert" :class="safety.available ? 'info' : 'warn'">{{ safety.message }}</div>
                <div class="alert warn" v-if="safety.degraded">{{ safety.degradedReason }}</div>
                <div class="hits">
                    <div class="hit" v-for="(h, i) in safety.hits" :key="i">
                        <div class="hit-head">
                            <strong>第 {{ h.sectionNo }} 节 · {{ h.sectionTitle }}</strong>
                            <span class="hint">score {{ score(h.score) }}</span>
                        </div>
                        <div class="hit-body">{{ h.content }}</div>
                    </div>
                </div>
            </div>
        </div>

        <div class="card">
            <h2>禁忌混放检查</h2>
            <div v-if="incompat" style="margin-top:10px">
                <div class="alert" :class="incompat.coStoredPairCount > 0 ? 'bad' : 'info'">{{ incompat.message }}</div>
                <div v-for="(c, i) in incompat.conflicts" :key="'c' + i" class="cite">
                    同柜：{{ c.chemicalA.name }} × {{ c.chemicalB.name }}（{{ c.sharedLocations.join('、') }}）<br>
                    原因：{{ c.reason }}
                </div>
                <div v-for="(c, i) in incompat.separatedConflicts" :key="'s' + i" class="cite">
                    分柜：{{ c.chemicalA.name }} × {{ c.chemicalB.name }}，须继续保持分离
                </div>
            </div>
        </div>
    </div>
</section>`
    });

    /* ---------------- 效期预警 ---------------- */

    app.component('expiring-page', {
        mixins: [pageMixin],
        data() {
            return { withinDays: 30, page: 0, result: null };
        },
        created() {
            this.load(0);
        },
        methods: {
            async load(page) {
                const target = typeof page === 'number' ? page : this.page;
                await this.run(async () => {
                    this.result = await api('GET', '/api/chemicals/expiring' + qs({ withinDays: this.withinDays, page: target, size: 20 }));
                    this.page = target;
                });
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>效期预警</h1>
            <p class="hint">按「可用截止日」排序：开封后可用期可能早于包装有效期。</p>
        </div>
        <form class="form-actions" @submit.prevent="load(0)">
            <label class="field" style="margin:0;width:120px"><span>未来天数</span>
                <input v-model.number="withinDays" type="number" min="1" max="365">
            </label>
            <button class="btn" type="submit">刷新</button>
        </form>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <div class="card">
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>批次</th><th>试剂</th><th>位置</th><th class="num">可用量</th>
                    <th>有效期</th><th>可用截止日</th><th>剩余天数</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="b in (result ? result.content : [])" :key="b.batchId">
                    <td class="mono">{{ b.batchNo }}</td>
                    <td><a :href="'#/chemicals/' + b.chemicalId">{{ b.chemicalName }}</a></td>
                    <td>{{ b.location }}</td>
                    <td class="num">{{ b.availableQuantity }} {{ b.unit }}</td>
                    <td>{{ b.expireDate }}</td>
                    <td>{{ b.usableUntil }}</td>
                    <td>
                        <span class="badge" :class="b.daysToUsable < 0 ? 'bad' : 'warn'">
                            {{ b.daysToUsable < 0 ? '已过期 ' + (-b.daysToUsable) + ' 天' : b.daysToUsable + ' 天' }}
                        </span>
                    </td>
                </tr>
                <tr v-if="result && result.content.length === 0">
                    <td colspan="7" class="empty">该时间范围内没有需要处理的批次</td>
                </tr>
                </tbody>
            </table>
        </div>
        <pager :page="result" @change="load"></pager>
    </div>
</section>`
    });

    /* ---------------- 领用 ---------------- */

    app.component('requisitions-page', {
        mixins: [pageMixin],
        data() {
            return {
                status: '',
                applicant: '',
                page: 0,
                result: null,
                chemicals: [],
                showCreate: false,
                form: { chemicalId: '', quantity: '', purpose: '' }
            };
        },
        created() {
            this.load(0);
            this.loadChemicals();
        },
        methods: {
            async load(page) {
                const target = typeof page === 'number' ? page : this.page;
                await this.run(async () => {
                    this.result = await api('GET', '/api/requisitions' + qs({
                        status: this.status, applicant: this.applicant, page: target, size: 20
                    }));
                    this.page = target;
                });
            },
            async loadChemicals() {
                try {
                    const page = await api('GET', '/api/chemicals' + qs({ page: 0, size: 100 }));
                    this.chemicals = page.content;
                } catch (e) {
                    /* 建单表单的下拉框加载失败不阻塞列表 */
                }
            },
            async submit() {
                const body = {
                    chemicalId: Number(this.form.chemicalId),
                    quantity: Number(this.form.quantity),
                    purpose: this.form.purpose
                };
                const saved = await this.act('create', () => api('POST', '/api/requisitions', body));
                if (saved) {
                    notify('已提交申请 ' + saved.reqNo);
                    this.showCreate = false;
                    this.form = { chemicalId: '', quantity: '', purpose: '' };
                    this.load(0);
                }
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>领用申请</h1>
            <p class="hint">普通用户默认只看自己的；审批员填申请人用户名可查他人。</p>
        </div>
        <button class="btn" @click="showCreate = !showCreate">{{ showCreate ? '收起表单' : '提交申请' }}</button>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <form v-if="showCreate" class="card form-grid" @submit.prevent="submit">
        <label class="field"><span>试剂 *</span>
            <select v-model="form.chemicalId" required>
                <option value="" disabled>请选择</option>
                <option v-for="c in chemicals" :key="c.id" :value="String(c.id)">
                    {{ c.name }}（{{ c.casNo }}，{{ c.unit }}）
                </option>
            </select>
        </label>
        <label class="field"><span>领用量 *</span><input v-model="form.quantity" type="number" step="0.001" min="0.001" required></label>
        <label class="field"><span>用途 *</span><input v-model.trim="form.purpose" maxlength="500" required></label>
        <div class="form-actions" style="grid-column:1/-1">
            <button class="btn" type="submit" :disabled="busy === 'create'">
                {{ busy === 'create' ? '提交中…' : '提交申请' }}
            </button>
        </div>
    </form>

    <form class="card filters" @submit.prevent="load(0)">
        <label class="field"><span>状态</span>
            <select v-model="status">
                <option value="">全部</option>
                <option v-for="s in ['PENDING','PENDING_SECOND','APPROVED','ISSUED','CLOSED','REJECTED','CANCELLED']" :key="s" :value="s">
                    {{ statusLabel(s) }}
                </option>
            </select>
        </label>
        <label class="field"><span>申请人（留空 = 本人）</span><input v-model.trim="applicant" placeholder="用户名"></label>
        <div class="form-actions"><button class="btn" type="submit" :disabled="loading">查询</button></div>
    </form>

    <div class="card">
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>单号</th><th>试剂 ID</th><th class="num">数量</th><th>申请人</th>
                    <th>状态</th><th>提交时间</th><th>审批人</th><th></th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="r in (result ? result.content : [])" :key="r.id">
                    <td class="mono"><a :href="'#/requisitions/' + r.reqNo">{{ r.reqNo }}</a></td>
                    <td>{{ r.chemicalId }}</td>
                    <td class="num">{{ r.quantity }}</td>
                    <td>{{ r.applicant }}</td>
                    <td><span class="badge" :class="statusClass(r.status)">{{ statusLabel(r.status) }}</span></td>
                    <td>{{ dt(r.createdAt) }}</td>
                    <td>{{ num(r.reviewer) }}</td>
                    <td class="actions"><a :href="'#/requisitions/' + r.reqNo">详情</a></td>
                </tr>
                <tr v-if="result && result.content.length === 0">
                    <td colspan="8" class="empty">没有符合条件的申请</td>
                </tr>
                </tbody>
            </table>
        </div>
        <pager :page="result" @change="load"></pager>
    </div>
</section>`
    });

    app.component('requisition-detail-page', {
        mixins: [pageMixin],
        data() {
            return { detail: null, safety: null, comment: '' };
        },
        computed: {
            req() {
                return this.detail ? this.detail.requisition : null;
            },
            canReview() {
                return this.req && this.isApprover
                    && (this.req.status === 'PENDING' || this.req.status === 'PENDING_SECOND')
                    && this.req.applicant !== this.me;
            },
            canCancel() {
                return this.req && this.req.applicant === this.me
                    && ['PENDING', 'PENDING_SECOND', 'APPROVED'].indexOf(this.req.status) >= 0;
            }
        },
        created() {
            this.load();
        },
        methods: {
            async load() {
                const data = await this.run(async () => {
                    const [detail, safety] = await Promise.all([
                        api('GET', '/api/requisitions/' + this.routeParam),
                        api('GET', '/api/requisitions/' + this.routeParam + '/safety')
                    ]);
                    return { detail, safety };
                });
                if (data) {
                    this.detail = data.detail;
                    this.safety = data.safety;
                }
            },
            async review(approved) {
                const done = await this.act('review', () => api(
                    'POST', '/api/requisitions/' + this.routeParam + '/review',
                    { approved, comment: this.comment || null }));
                if (done) {
                    notify(approved ? '已通过' : '已驳回');
                    this.comment = '';
                    this.load();
                }
            },
            async action(verb, label) {
                const done = await this.act(verb, () => api(
                    'POST', '/api/requisitions/' + this.routeParam + '/' + verb));
                if (done) {
                    notify(label);
                    this.load();
                }
            }
        },
        template: `
<div v-if="error && !req" class="alert error">{{ error }}</div>
<section v-if="req">
    <div class="page-head">
        <div>
            <h1>领用申请 <span class="mono">{{ req.reqNo }}</span></h1>
            <p class="hint">申请人 {{ req.applicant }} · 提交于 {{ dt(req.createdAt) }}</p>
        </div>
        <a class="btn ghost" href="#/requisitions">返回列表</a>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <div class="card">
        <div class="kv">
            <div><div class="k">状态</div><div class="v"><span class="badge" :class="statusClass(req.status)">{{ statusLabel(req.status) }}</span></div></div>
            <div><div class="k">试剂 ID</div><div class="v">{{ req.chemicalId }}</div></div>
            <div><div class="k">申请量</div><div class="v">{{ req.quantity }}</div></div>
            <div><div class="k">审批人</div><div class="v">{{ num(req.reviewer) }}</div></div>
            <div><div class="k">第一位审批人</div><div class="v">{{ num(req.firstReviewer) }}</div></div>
            <div><div class="k">出库批次</div><div class="v">{{ num(req.issuedBatchId) }}</div></div>
            <div><div class="k">出库量</div><div class="v">{{ num(req.issuedQuantity) }}</div></div>
            <div><div class="k">用途</div><div class="v">{{ req.purpose }}</div></div>
        </div>
        <div v-if="req.reviewComment" class="alert info" style="margin-top:12px">审批意见：{{ req.reviewComment }}</div>
    </div>

    <div class="card">
        <h2>流转时间线</h2>
        <ul class="timeline" style="margin-top:10px">
            <li v-for="(t, i) in detail.timeline" :key="i">
                <div class="t-head">
                    {{ t.fromStatus ? statusLabel(t.fromStatus) : '提交' }} →
                    <strong>{{ statusLabel(t.toStatus) }}</strong>
                </div>
                <div class="t-meta">{{ dt(t.at) }} · {{ t.operator }}<span v-if="t.comment"> · {{ t.comment }}</span></div>
            </li>
        </ul>
    </div>

    <div class="card">
        <h2>操作</h2>
        <div v-if="canReview" style="margin-top:10px">
            <label class="field"><span>审批意见（可选）</span>
                <input v-model.trim="comment" maxlength="500" placeholder="通过或驳回的理由">
            </label>
            <div class="form-actions">
                <button class="btn ok" :disabled="busy === 'review'" @click="review(true)">通过</button>
                <button class="btn bad" :disabled="busy === 'review'" @click="review(false)">驳回</button>
            </div>
            <p class="hint" style="margin-top:6px">
                高危试剂的第一次审批会进入「待二级复核」，需要第二位审批人再通过。
            </p>
        </div>
        <div v-else class="hint">当前状态或角色没有可执行的审批动作。</div>
        <div class="form-actions" style="margin-top:12px">
            <button v-if="isAdmin && req.status === 'APPROVED'" class="btn ok" :disabled="busy === 'issue'"
                    @click="action('issue', '已出库')">出库</button>
            <button v-if="isAdmin && req.status === 'ISSUED'" class="btn" :disabled="busy === 'close'"
                    @click="action('close', '已核销')">核销</button>
            <button v-if="canCancel" class="btn ghost" :disabled="busy === 'cancel'"
                    @click="action('cancel', '已撤回')">撤回申请</button>
        </div>
    </div>

    <div class="card" v-if="safety">
        <h2>安全摘要</h2>
        <div class="alert" :class="safety.available ? 'info' : 'warn'" style="margin-top:10px">{{ safety.message }}</div>
        <div class="section" v-for="s in safety.sections" :key="s.sectionNo">
            <h3>第 {{ s.sectionNo }} 节 · {{ s.title }}</h3>
            <pre>{{ s.content }}</pre>
        </div>
    </div>
</section>`
    });

    /* ---------------- 盘点 ---------------- */

    app.component('stocktakes-page', {
        mixins: [pageMixin],
        data() {
            return {
                status: '',
                page: 0,
                result: null,
                showCreate: false,
                form: { remark: '', chemicalIds: '' }
            };
        },
        created() {
            this.load(0);
        },
        methods: {
            async load(page) {
                const target = typeof page === 'number' ? page : this.page;
                await this.run(async () => {
                    this.result = await api('GET', '/api/stocktakes' + qs({ status: this.status, page: target, size: 20 }));
                    this.page = target;
                });
            },
            async create() {
                const ids = this.form.chemicalIds
                    ? this.form.chemicalIds.split(',').map(v => Number(v.trim())).filter(v => v > 0)
                    : [];
                const body = { chemicalIds: ids.length ? ids : null, remark: this.form.remark || null };
                const saved = await this.act('create', () => api('POST', '/api/stocktakes', body));
                if (saved) {
                    notify('已建盘点单 ' + saved.stocktakeNo);
                    this.showCreate = false;
                    this.form = { remark: '', chemicalIds: '' };
                    go('/stocktakes/' + saved.stocktakeNo);
                }
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>盘点</h1>
            <p class="hint">建单时留空试剂 ID 表示盘点全部有库存的批次。</p>
        </div>
        <button v-if="isAdmin" class="btn" @click="showCreate = !showCreate">{{ showCreate ? '收起表单' : '新建盘点单' }}</button>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <form v-if="showCreate" class="card form-grid" @submit.prevent="create">
        <label class="field"><span>备注</span><input v-model.trim="form.remark" maxlength="500"></label>
        <label class="field"><span>试剂 ID（逗号分隔，留空 = 全部）</span>
            <input v-model.trim="form.chemicalIds" placeholder="1, 2, 3">
        </label>
        <div class="form-actions" style="grid-column:1/-1">
            <button class="btn" type="submit" :disabled="busy === 'create'">
                {{ busy === 'create' ? '创建中…' : '创建' }}
            </button>
        </div>
    </form>

    <form class="card filters" @submit.prevent="load(0)">
        <label class="field"><span>状态</span>
            <select v-model="status">
                <option value="">全部</option>
                <option v-for="s in ['DRAFT','SUBMITTED','REVIEWED','CONFIRMED','CANCELLED']" :key="s" :value="s">
                    {{ statusLabel(s) }}
                </option>
            </select>
        </label>
        <div class="form-actions"><button class="btn" type="submit">查询</button></div>
    </form>

    <div class="card">
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>单号</th><th>状态</th><th>创建人</th><th>创建时间</th>
                    <th>提交人</th><th>提交时间</th><th>确认人</th><th>备注</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="s in (result ? result.content : [])" :key="s.stocktakeNo">
                    <td class="mono"><a :href="'#/stocktakes/' + s.stocktakeNo">{{ s.stocktakeNo }}</a></td>
                    <td><span class="badge" :class="statusClass(s.status)">{{ statusLabel(s.status) }}</span></td>
                    <td>{{ s.createdBy }}</td>
                    <td>{{ dt(s.createdAt) }}</td>
                    <td>{{ num(s.submittedBy) }}</td>
                    <td>{{ dt(s.submittedAt) }}</td>
                    <td>{{ num(s.confirmedBy) }}</td>
                    <td>{{ num(s.remark) }}</td>
                </tr>
                <tr v-if="result && result.content.length === 0">
                    <td colspan="8" class="empty">还没有盘点单</td>
                </tr>
                </tbody>
            </table>
        </div>
        <pager :page="result" @change="load"></pager>
    </div>
</section>`
    });

    app.component('stocktake-detail-page', {
        mixins: [pageMixin],
        data() {
            return { detail: null, edits: {} };
        },
        computed: {
            canEditItems() {
                return this.detail && this.isAdmin && this.detail.status === 'DRAFT';
            }
        },
        created() {
            this.load();
        },
        methods: {
            async load() {
                const data = await this.run(() => api('GET', '/api/stocktakes/' + this.routeParam));
                if (data) {
                    this.detail = data;
                    const edits = {};
                    data.items.forEach(item => {
                        edits[item.itemId] = {
                            actualQuantity: item.actualQuantity === null ? '' : item.actualQuantity,
                            note: item.note || ''
                        };
                    });
                    this.edits = edits;
                }
            },
            async save(item) {
                const edit = this.edits[item.itemId];
                // 空输入不能直接 Number()：Number('') 是 0，会把「还没数」写成实盘 0，
                // 确认后整批库存被调成 0。非法输入也在这里挡下，不发请求。
                const raw = String(edit.actualQuantity == null ? '' : edit.actualQuantity).trim();
                const actualQuantity = Number(raw);
                if (raw === '' || !Number.isFinite(actualQuantity)) {
                    notify('请先填写实盘量：' + item.batchNo);
                    return;
                }
                const body = {
                    actualQuantity: actualQuantity,
                    note: edit.note || null
                };
                const done = await this.act('item-' + item.itemId, () => api(
                    'PUT', '/api/stocktakes/' + this.routeParam + '/items/' + item.itemId, body));
                if (done) {
                    notify('已记录：' + item.batchNo);
                    this.load();
                }
            },
            async action(verb, label) {
                const done = await this.act(verb, () => api(
                    'POST', '/api/stocktakes/' + this.routeParam + '/' + verb, verb === 'review' ? {} : undefined));
                if (done) {
                    notify(label);
                    this.load();
                }
            }
        },
        template: `
<div v-if="error && !detail" class="alert error">{{ error }}</div>
<section v-if="detail">
    <div class="page-head">
        <div>
            <h1>盘点单 <span class="mono">{{ detail.stocktakeNo }}</span></h1>
            <p class="hint">
                状态 <span class="badge" :class="statusClass(detail.status)">{{ statusLabel(detail.status) }}</span>
                · 创建人 {{ detail.createdBy }} · {{ dt(detail.createdAt) }}
                <span v-if="detail.diffCount !== null"> · 差异 {{ detail.diffCount }} 条</span>
            </p>
        </div>
        <a class="btn ghost" href="#/stocktakes">返回列表</a>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>
    <div v-if="detail" class="alert info" v-show="detail.remark">备注：{{ detail.remark }}</div>

    <div class="card">
        <div class="card-head">
            <h2>盘点明细</h2>
            <div class="form-actions">
                <button v-if="isAdmin && detail.status === 'DRAFT'" class="btn" :disabled="busy === 'submit'"
                        @click="action('submit', '已提交')">提交</button>
                <button v-if="isAdmin && detail.status === 'SUBMITTED'" class="btn" :disabled="busy === 'review'"
                        @click="action('review', '已复核')">复核</button>
                <button v-if="isAdmin && (detail.status === 'SUBMITTED' || detail.status === 'REVIEWED')" class="btn ok"
                        :disabled="busy === 'confirm'" @click="action('confirm', '已确认并调账')">确认</button>
                <button v-if="isAdmin && detail.status !== 'CONFIRMED' && detail.status !== 'CANCELLED'" class="btn ghost"
                        :disabled="busy === 'cancel'" @click="action('cancel', '已撤销')">撤销</button>
            </div>
        </div>
        <p class="hint" style="margin-bottom:10px">
            是否必须先复核由部署开关决定；默认关闭时，已提交的单子可以直接确认。
        </p>
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>批次</th><th>试剂</th><th class="num">账面量</th><th class="num">实盘量</th>
                    <th class="num">差异</th><th>说明</th><th v-if="canEditItems">录入</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="item in detail.items" :key="item.itemId">
                    <td class="mono">{{ item.batchNo }}</td>
                    <td>{{ item.chemicalName }}</td>
                    <td class="num">{{ item.bookQuantity }} {{ item.unit }}</td>
                    <td class="num">
                        <span v-if="canEditItems">
                            <input v-model="edits[item.itemId].actualQuantity" type="number" step="0.001" min="0"
                                   style="width:110px;display:inline-block">
                        </span>
                        <span v-else>{{ item.actualQuantity === null ? '未录入' : item.actualQuantity }}</span>
                    </td>
                    <td class="num">
                        <span v-if="item.diffQuantity === null" class="hint">—</span>
                        <span v-else class="badge" :class="item.diffQuantity === 0 ? 'ok' : 'warn'">{{ item.diffQuantity }}</span>
                    </td>
                    <td>
                        <span v-if="canEditItems">
                            <input v-model.trim="edits[item.itemId].note" maxlength="500" placeholder="差异说明">
                        </span>
                        <span v-else>{{ num(item.note) }}</span>
                    </td>
                    <td v-if="canEditItems">
                        <button class="btn ghost sm" :disabled="busy === 'item-' + item.itemId"
                                @click="save(item)">保存</button>
                    </td>
                </tr>
                <tr v-if="detail.items.length === 0">
                    <td colspan="7" class="empty">这张盘点单没有明细</td>
                </tr>
                </tbody>
            </table>
        </div>
    </div>

    <div class="card">
        <h2>签字记录</h2>
        <div class="kv" style="margin-top:10px">
            <div><div class="k">提交</div><div class="v">{{ num(detail.submittedBy) }} · {{ dt(detail.submittedAt) }}</div></div>
            <div><div class="k">复核</div><div class="v">{{ num(detail.reviewedBy) }} · {{ dt(detail.reviewedAt) }}</div></div>
            <div><div class="k">确认</div><div class="v">{{ num(detail.confirmedBy) }} · {{ dt(detail.confirmedAt) }}</div></div>
        </div>
    </div>
</section>`
    });

    /* ---------------- 报废 ---------------- */

    app.component('scraps-page', {
        mixins: [pageMixin],
        data() {
            return {
                status: '',
                applicant: '',
                page: 0,
                result: null,
                showCreate: false,
                chemicalId: '',
                batches: [],
                form: { batchId: '', quantity: '', reason: '' },
                comment: ''
            };
        },
        created() {
            this.load(0);
        },
        methods: {
            async load(page) {
                const target = typeof page === 'number' ? page : this.page;
                await this.run(async () => {
                    this.result = await api('GET', '/api/scraps' + qs({
                        status: this.status, applicant: this.applicant, page: target, size: 20
                    }));
                    this.page = target;
                });
            },
            async loadBatches() {
                if (!this.chemicalId) {
                    return;
                }
                const data = await this.act('batches', () => api('GET', '/api/chemicals/' + this.chemicalId + '/stock'));
                if (data) {
                    this.batches = data.batches.filter(b => Number(b.availableQuantity) > 0);
                    this.form.batchId = '';
                    if (this.batches.length === 0) {
                        this.error = '该试剂当前没有可用批次';
                    }
                }
            },
            async submit() {
                const body = {
                    chemicalId: Number(this.chemicalId),
                    batchId: Number(this.form.batchId),
                    quantity: Number(this.form.quantity),
                    reason: this.form.reason
                };
                const saved = await this.act('create', () => api('POST', '/api/scraps', body));
                if (saved) {
                    notify('已提交报废申请 ' + saved.scrapNo);
                    this.showCreate = false;
                    this.chemicalId = '';
                    this.batches = [];
                    this.form = { batchId: '', quantity: '', reason: '' };
                    this.load(0);
                }
            },
            async review(order, approved) {
                const done = await this.act('review-' + order.id, () => api(
                    'POST', '/api/scraps/' + order.scrapNo + '/review',
                    { approved, comment: this.comment || null }));
                if (done) {
                    notify(approved ? '已通过并扣减库存' : '已驳回');
                    this.comment = '';
                    this.load();
                }
            },
            async cancel(order) {
                const done = await this.act('cancel-' + order.id, () => api('POST', '/api/scraps/' + order.scrapNo + '/cancel'));
                if (done) {
                    notify('已撤销');
                    this.load();
                }
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>报废申请</h1>
            <p class="hint">申请 → 审批 → 扣减。管理员应急直接报废在试剂详情页。</p>
        </div>
        <button class="btn" @click="showCreate = !showCreate">{{ showCreate ? '收起表单' : '提交报废申请' }}</button>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <form v-if="showCreate" class="card form-grid" @submit.prevent="submit">
        <label class="field"><span>试剂 ID *</span>
            <input v-model.trim="chemicalId" type="number" min="1" required>
        </label>
        <div class="form-actions" style="align-self:end;padding-bottom:10px">
            <button class="btn ghost" type="button" :disabled="busy === 'batches'" @click="loadBatches">加载可报废批次</button>
        </div>
        <label class="field"><span>批次 *</span>
            <select v-model="form.batchId" required>
                <option value="" disabled>请先加载批次</option>
                <option v-for="b in batches" :key="b.id" :value="String(b.id)">
                    {{ b.batchNo }}（可用 {{ b.availableQuantity }}，{{ b.location }}）
                </option>
            </select>
        </label>
        <label class="field"><span>报废量 *</span><input v-model="form.quantity" type="number" step="0.001" min="0.001" required></label>
        <label class="field" style="grid-column:1/-1"><span>原因 *</span><input v-model.trim="form.reason" maxlength="500" required></label>
        <div class="form-actions" style="grid-column:1/-1">
            <button class="btn" type="submit" :disabled="busy === 'create'">
                {{ busy === 'create' ? '提交中…' : '提交申请' }}
            </button>
        </div>
    </form>

    <form class="card filters" @submit.prevent="load(0)">
        <label class="field"><span>状态</span>
            <select v-model="status">
                <option value="">全部</option>
                <option v-for="s in ['PENDING','APPROVED','REJECTED','CANCELLED']" :key="s" :value="s">
                    {{ statusLabel(s) }}
                </option>
            </select>
        </label>
        <label class="field"><span>申请人（留空 = 本人；审批员留空 = 全部）</span>
            <input v-model.trim="applicant" placeholder="用户名">
        </label>
        <label class="field"><span>审批意见（可选）</span><input v-model.trim="comment" maxlength="500"></label>
        <div class="form-actions"><button class="btn" type="submit">查询</button></div>
    </form>

    <div class="card">
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>单号</th><th>试剂</th><th>批次</th><th class="num">数量</th><th>原因</th>
                    <th>状态</th><th>申请人</th><th>提交时间</th><th>操作</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="o in (result ? result.content : [])" :key="o.id">
                    <td class="mono">{{ o.scrapNo }}</td>
                    <td>{{ o.chemicalId }}</td>
                    <td class="mono">{{ o.batchNo }}</td>
                    <td class="num">{{ o.quantity }}</td>
                    <td>{{ o.reason }}</td>
                    <td><span class="badge" :class="statusClass(o.status)">{{ statusLabel(o.status) }}</span></td>
                    <td>{{ o.applicant }}</td>
                    <td>{{ dt(o.createdAt) }}</td>
                    <td class="actions">
                        <template v-if="isApprover && o.status === 'PENDING' && o.applicant !== me">
                            <button class="btn ok sm" :disabled="busy === 'review-' + o.id" @click="review(o, true)">通过</button>
                            <button class="btn bad sm" :disabled="busy === 'review-' + o.id" @click="review(o, false)">驳回</button>
                        </template>
                        <button v-if="o.applicant === me && o.status === 'PENDING'" class="btn ghost sm"
                                :disabled="busy === 'cancel-' + o.id" @click="cancel(o)">撤销</button>
                    </td>
                </tr>
                <tr v-if="result && result.content.length === 0">
                    <td colspan="9" class="empty">没有符合条件的报废单</td>
                </tr>
                </tbody>
            </table>
        </div>
        <pager :page="result" @change="load"></pager>
    </div>
</section>`
    });

    /* ---------------- MSDS ---------------- */

    app.component('msds-page', {
        mixins: [pageMixin],
        data() {
            return {
                search: { q: '', casNo: '', docType: '', topK: 5 },
                searchResult: null,
                ask: { q: '', casNo: '', topK: 5 },
                answer: null,
                status: null,
                tasks: []
            };
        },
        created() {
            if (this.isAdmin) {
                this.loadAdmin();
            }
        },
        methods: {
            async runSearch() {
                const result = await this.run(() => api('GET', '/api/msds/search' + qs({
                    q: this.search.q, casNo: this.search.casNo, docType: this.search.docType, topK: this.search.topK
                })));
                if (result) {
                    this.searchResult = result;
                }
            },
            async runAsk() {
                const result = await this.run(() => api('GET', '/api/msds/ask' + qs({
                    q: this.ask.q, casNo: this.ask.casNo, topK: this.ask.topK
                })));
                if (result) {
                    this.answer = result;
                }
            },
            async loadAdmin() {
                try {
                    const [status, tasks] = await Promise.all([
                        api('GET', '/api/msds/status'),
                        api('GET', '/api/msds/encode-tasks' + qs({ limit: 20 }))
                    ]);
                    this.status = status;
                    this.tasks = tasks;
                } catch (e) {
                    this.error = e.message;
                }
            },
            async adminAction(path, label) {
                if (!window.confirm('确认执行「' + label + '」？')) {
                    return;
                }
                const done = await this.act(path, () => api('POST', '/api/msds/' + path));
                if (done) {
                    notify(label + '已完成');
                    this.loadAdmin();
                }
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>MSDS 检索与问答</h1>
            <p class="hint">数据来源为本地 MSDS 文本片段；摘要与生成结论不是正式安全依据。</p>
        </div>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <div class="grid-2">
        <form class="card" @submit.prevent="runSearch">
            <h2>片段检索</h2>
            <label class="field" style="margin-top:10px"><span>关键词或问题 *</span>
                <input v-model.trim="search.q" required placeholder="例如：甲醇 急救">
            </label>
            <div class="form-grid">
                <label class="field"><span>CAS 号（可选）</span><input v-model.trim="search.casNo"></label>
                <label class="field"><span>知识源</span>
                    <select v-model="search.docType">
                        <option value="">全部</option><option>MSDS</option><option>GB</option><option>SOP</option>
                    </select>
                </label>
                <label class="field"><span>返回条数</span><input v-model.number="search.topK" type="number" min="1" max="20"></label>
            </div>
            <button class="btn" type="submit" :disabled="loading">检索</button>
        </form>

        <form class="card" @submit.prevent="runAsk">
            <h2>检索并作答</h2>
            <label class="field" style="margin-top:10px"><span>问题 *</span>
                <input v-model.trim="ask.q" required placeholder="例如：甲醇误食怎么办">
            </label>
            <div class="form-grid">
                <label class="field"><span>CAS 号（可选）</span><input v-model.trim="ask.casNo"></label>
                <label class="field"><span>参考条数</span><input v-model.number="ask.topK" type="number" min="1" max="20"></label>
            </div>
            <button class="btn" type="submit" :disabled="loading">作答</button>
        </form>
    </div>

    <div class="card" v-if="searchResult">
        <h2>检索结果</h2>
        <div v-if="searchResult.degraded" class="alert warn">{{ searchResult.degradedReason }}</div>
        <div v-if="searchResult.needsRebuild" class="alert warn">索引需要重建，结果可能不完整。</div>
        <div class="hits" style="margin-top:10px">
            <div class="hit" v-for="(h, i) in searchResult.hits" :key="i">
                <div class="hit-head">
                    <strong>{{ h.chemicalName }} · 第 {{ h.sectionNo }} 节 · {{ h.sectionTitle }}</strong>
                    <span class="hint">score {{ score(h.score) }}<span v-if="h.docType"> · {{ h.docType }}</span></span>
                </div>
                <div class="hit-body">{{ h.content }}</div>
                <div class="hint">来源：{{ h.sources.join('、') }}</div>
            </div>
            <div v-if="searchResult.hits.length === 0" class="empty">没有命中片段</div>
        </div>
    </div>

    <div class="card" v-if="answer">
        <h2>问答结果</h2>
        <div class="alert" :class="answer.answerable ? 'info' : 'warn'" style="margin-top:10px">
            {{ answer.answerable ? (answer.generated ? '由模型基于检索片段生成' : '未配置生成模型，返回原文片段') : '无法确认答案' }}
        </div>
        <div v-if="answer.degraded" class="alert warn">{{ answer.degradedReason }}</div>
        <pre v-if="answer.answer">{{ answer.answer }}</pre>
        <div class="cite" v-for="c in answer.citations" :key="c.index">
            [{{ c.index }}] {{ c.chemicalName }} · 第 {{ c.sectionNo }} 节 {{ c.sectionTitle }}<br>
            {{ c.content }}
        </div>
    </div>

    <div class="card" v-if="isAdmin">
        <div class="card-head">
            <h2>索引运维（管理员）</h2>
            <div class="form-actions">
                <button class="btn" :disabled="busy === 'reindex'" @click="adminAction('reindex', '重建索引')">重建索引</button>
                <button class="btn ghost" :disabled="busy === 'reconcile'" @click="adminAction('reconcile', '向量对账')">向量对账</button>
                <button class="btn ghost" :disabled="busy === 'rollback'" @click="adminAction('rollback', '回滚索引')">回滚上一版</button>
                <button class="btn ghost" @click="loadAdmin">刷新状态</button>
            </div>
        </div>
        <div v-if="status" class="kv">
            <div><div class="k">块数 / 已编码 / 待编码 / 失败</div><div class="v">{{ status.chunks }} / {{ status.encoded }} / {{ status.pending }} / {{ status.failed }}</div></div>
            <div><div class="k">当前版本</div><div class="v mono">{{ num(status.activeVersion) }}</div></div>
            <div><div class="k">上一版本</div><div class="v mono">{{ num(status.previousVersion) }}</div></div>
            <div><div class="k">向量存储</div><div class="v">{{ status.vectorStore }}（{{ status.vectorRows }} 行）</div></div>
            <div><div class="k">模型</div><div class="v">{{ status.model }} · {{ status.dimensions }} 维</div></div>
            <div><div class="k">状态</div><div class="v">
                <span class="badge" :class="status.paused ? 'bad' : (status.encodingInProgress ? 'warn' : 'ok')">
                    {{ status.paused ? '已暂停' : (status.encodingInProgress ? '编码中' : '就绪') }}
                </span>
                <span v-if="status.needsRebuild" class="badge warn">需重建</span>
            </div></div>
        </div>
        <div v-if="status && status.pausedReason" class="alert warn" style="margin-top:10px">{{ status.pausedReason }}</div>

        <h3 style="margin:14px 0 6px">最近编码任务</h3>
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr><th>ID</th><th>版本</th><th>状态</th><th>尝试</th><th>失败原因</th><th>完成时间</th></tr>
                </thead>
                <tbody>
                <tr v-for="t in tasks" :key="t.id">
                    <td class="mono">{{ t.id }}</td>
                    <td class="mono">{{ t.indexVersion }}</td>
                    <td><span class="badge" :class="statusClass(t.status)">{{ statusLabel(t.status) }}</span></td>
                    <td>{{ t.attempts }} / {{ t.maxAttempts }}</td>
                    <td>{{ num(t.lastError) }}</td>
                    <td>{{ dt(t.finishedAt) }}</td>
                </tr>
                <tr v-if="tasks.length === 0">
                    <td colspan="6" class="empty">没有编码任务（MQ 关闭时不会创建任务行）</td>
                </tr>
                </tbody>
            </table>
        </div>
    </div>
</section>`
    });

    /* ---------------- 运维告警 ---------------- */

    app.component('ops-page', {
        mixins: [pageMixin],
        data() {
            return { status: 'OPEN', type: '', page: 0, result: null };
        },
        created() {
            this.load(0);
        },
        methods: {
            async load(page) {
                const target = typeof page === 'number' ? page : this.page;
                const data = await this.run(() => api('GET', '/api/ops/alerts' + qs({
                    status: this.status, type: this.type, page: target, size: 20
                })));
                if (data) {
                    this.result = data;
                    this.page = target;
                }
            },
            async resolve(alert) {
                const done = await this.act('resolve-' + alert.id, () => api(
                    'POST', '/api/ops/alerts/' + alert.id + '/resolve'));
                if (done) {
                    notify('已解决：' + alert.alertType);
                    this.load();
                }
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>运维告警</h1>
            <p class="hint">定时任务把发现写成可查询、可解决的告警；同一指纹只保留一行，重复出现会累加次数。</p>
        </div>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <form class="card filters" @submit.prevent="load(0)">
        <label class="field"><span>状态</span>
            <select v-model="status">
                <option value="OPEN">未解决</option>
                <option value="RESOLVED">已解决</option>
                <option value="">全部</option>
            </select>
        </label>
        <label class="field"><span>类型（可选）</span><input v-model.trim="type" placeholder="EXPIRY / RECONCILE ..."></label>
        <div class="form-actions"><button class="btn" type="submit">查询</button></div>
    </form>

    <div class="card">
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr>
                    <th>类型</th><th>级别</th><th>内容</th><th class="num">次数</th>
                    <th>首次</th><th>最近</th><th>状态</th><th></th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="a in (result ? result.content : [])" :key="a.id">
                    <td class="mono">{{ a.alertType }}</td>
                    <td><span class="badge" :class="a.severity === 'CRITICAL' ? 'bad' : (a.severity === 'WARN' ? 'warn' : 'muted')">{{ a.severity }}</span></td>
                    <td>{{ a.message }}</td>
                    <td class="num">{{ a.occurrences }}</td>
                    <td>{{ dt(a.firstSeenAt) }}</td>
                    <td>{{ dt(a.lastSeenAt) }}</td>
                    <td>
                        <span class="badge" :class="a.resolved ? 'ok' : 'warn'">{{ a.resolved ? '已解决' : '未解决' }}</span>
                    </td>
                    <td class="actions">
                        <button v-if="!a.resolved" class="btn ghost sm" :disabled="busy === 'resolve-' + a.id"
                                @click="resolve(a)">标记解决</button>
                    </td>
                </tr>
                <tr v-if="result && result.content.length === 0">
                    <td colspan="8" class="empty">没有符合条件的告警</td>
                </tr>
                </tbody>
            </table>
        </div>
        <pager :page="result" @change="load"></pager>
    </div>
</section>`
    });

    /* ---------------- 用户管理 ---------------- */

    app.component('users-page', {
        mixins: [pageMixin],
        data() {
            return {
                users: [],
                showCreate: false,
                form: { username: '', displayName: '', role: 'USER', password: '' }
            };
        },
        created() {
            this.load();
        },
        methods: {
            async load() {
                const users = await this.run(() => api('GET', '/api/users'));
                if (users) {
                    this.users = users;
                }
            },
            async create() {
                const saved = await this.act('create', () => api('POST', '/api/users', this.form));
                if (saved) {
                    notify('已创建用户 ' + saved.username);
                    this.showCreate = false;
                    this.form = { username: '', displayName: '', role: 'USER', password: '' };
                    this.load();
                }
            },
            async toggle(user) {
                const done = await this.act('toggle-' + user.id, () => api(
                    'PATCH', '/api/users/' + user.id, { enabled: !user.enabled }));
                if (done) {
                    notify((done.enabled ? '已启用 ' : '已停用 ') + done.username);
                    this.load();
                }
            }
        },
        template: `
<section>
    <div class="page-head">
        <div>
            <h1>用户管理</h1>
            <p class="hint">自助注册的账号默认停用，需要在这里启用。停用后该账号的 token 立即失效。</p>
        </div>
        <button class="btn" @click="showCreate = !showCreate">{{ showCreate ? '收起表单' : '新建用户' }}</button>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>

    <form v-if="showCreate" class="card form-grid" @submit.prevent="create">
        <label class="field"><span>用户名 *</span><input v-model.trim="form.username" required></label>
        <label class="field"><span>显示名 *</span><input v-model.trim="form.displayName" required></label>
        <label class="field"><span>角色 *</span>
            <select v-model="form.role">
                <option value="USER">普通用户</option>
                <option value="APPROVER">审批员</option>
                <option value="ADMIN">管理员</option>
            </select>
        </label>
        <label class="field"><span>初始密码 *（8~64 位）</span>
            <input v-model="form.password" type="password" minlength="8" maxlength="64" required>
        </label>
        <div class="form-actions" style="grid-column:1/-1">
            <button class="btn" type="submit" :disabled="busy === 'create'">
                {{ busy === 'create' ? '创建中…' : '创建' }}
            </button>
        </div>
    </form>

    <div class="card">
        <div class="table-wrap">
            <table class="table">
                <thead>
                <tr><th>ID</th><th>用户名</th><th>显示名</th><th>角色</th><th>状态</th><th>创建时间</th><th></th></tr>
                </thead>
                <tbody>
                <tr v-for="u in users" :key="u.id">
                    <td class="mono">{{ u.id }}</td>
                    <td class="mono">{{ u.username }}</td>
                    <td>{{ u.displayName }}</td>
                    <td>{{ roleLabel(u.role) }}</td>
                    <td><span class="badge" :class="u.enabled ? 'ok' : 'muted'">{{ u.enabled ? '启用' : '停用' }}</span></td>
                    <td>{{ dt(u.createdAt) }}</td>
                    <td class="actions">
                        <button class="btn ghost sm" :disabled="busy === 'toggle-' + u.id || u.username === me"
                                @click="toggle(u)">{{ u.enabled ? '停用' : '启用' }}</button>
                    </td>
                </tr>
                <tr v-if="users.length === 0">
                    <td colspan="7" class="empty">没有用户</td>
                </tr>
                </tbody>
            </table>
        </div>
    </div>
</section>`
    });

    app.mount('#app');
})();
